package com.wixpress.dst.greyhound.core.consumer

import com.wixpress.dst.greyhound.core._
import com.wixpress.dst.greyhound.core.consumer.EventLoopMetric._
import com.wixpress.dst.greyhound.core.consumer.RecordConsumer.Env
import com.wixpress.dst.greyhound.core.metrics.{GreyhoundMetric, GreyhoundMetrics}
import com.wixpress.dst.greyhound.core.metrics.GreyhoundMetrics.report
import org.apache.kafka.clients.consumer.ConsumerRecords
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration._

import scala.collection.JavaConverters._

trait EventLoop[-R] extends Resource[R] {
  self =>
  def state: URIO[R with GreyhoundMetrics, DispatcherExposedState]
}

object EventLoop {
  type Handler[-R] = RecordHandler[R, Nothing, Chunk[Byte], Chunk[Byte]]

  def make[R1, R2](group: Group,
                   initialTopics: NonEmptySet[Topic],
                   consumer: Consumer[R1],
                   handler: Handler[R2],
                   clientId: ClientId,
                   config: EventLoopConfig = EventLoopConfig.Default): RManaged[R1 with R2 with GreyhoundMetrics with Env, EventLoop[R2 with GreyhoundMetrics with Clock]] = {
    val start = for {
      _ <- report(StartingEventLoop(clientId, group))
      offsets <- Offsets.make
      handle = handler.andThen(offsets.update).handle(_)
      dispatcher <- Dispatcher.make(group, clientId, handle, config.lowWatermark, config.highWatermark)
      pausedPartitionsRef <- Ref.make(Set.empty[TopicPartition])
      partitionsAssigned <- Promise.make[Nothing, Unit]
      // TODO how to handle errors in subscribe?
      runtime <- ZIO.runtime[R2 with GreyhoundMetrics with Clock]
      _ <- consumer.subscribe[Blocking with R1](
        topics = initialTopics,
        rebalanceListener = new RebalanceListener[Blocking with R1] {
          override def onPartitionsRevoked(partitions: Set[TopicPartition]): URIO[R1, Any] =
            ZIO.effectTotal {
              //todo: isn't this just boxing and unboxing? can't we eliminate it?
              /**
               * The rebalance listener is invoked while calling `poll`. Kafka forces you
               * to call `commit` from the same thread, otherwise an exception will be thrown.
               * This is needed in order to stay on the same thread and commit properly.
               * ZIO might decide to shift, which will make the call to `commit` fail,
               * however this is not very likely (it shifts every 1024 instructions by default),
               * and even when that happens we still maintain the guarantee to process at least once.
               */
              runtime.unsafeRun {
                pausedPartitionsRef.set(Set.empty) *>
                  config.rebalanceListener.onPartitionsRevoked(partitions) *>
                  dispatcher.revoke(partitions).timeout(config.drainTimeout).flatMap { drained =>
                    ZIO.when(drained.isEmpty)(report(DrainTimeoutExceeded(clientId, group)))
                  }
              }
            } *> commitOffsets(consumer, offsets, calledOnRebalance = true)

          override def onPartitionsAssigned(partitions: Set[TopicPartition]): URIO[R1, Any] =
            config.rebalanceListener.onPartitionsAssigned(partitions) *>
              partitionsAssigned.succeed(())
        })
      running <- Ref.make(true)
      fiber <- pollOnce(running, consumer, dispatcher, pausedPartitionsRef, offsets, config, clientId, group)
        .doWhile(_ == true).forkDaemon
      _ <- partitionsAssigned.await
    } yield (dispatcher, fiber, offsets, running)

    start.toManaged {
      case (dispatcher, fiber, offsets, running) => for {
        _ <- report(StoppingEventLoop(clientId, group))
        _ <- running.set(false)
        drained <- (fiber.join *> dispatcher.shutdown).timeout(config.drainTimeout)
        _ <- ZIO.when(drained.isEmpty)(report(DrainTimeoutExceeded(clientId, group)))
        _ <- commitOffsets(consumer, offsets)
      } yield ()
    }.map {
      case (dispatcher, fiber, _, _) =>
        new EventLoop[R2 with GreyhoundMetrics with Clock] {
          override def pause: URIO[R2 with GreyhoundMetrics with Clock, Unit] =
            report(PausingEventLoop(clientId, group)) *> dispatcher.pause

          override def resume: URIO[R2 with GreyhoundMetrics with Clock, Unit] =
            report(ResumingEventLoop(clientId, group)) *> dispatcher.resume

          override def isAlive: UIO[Boolean] = fiber.poll.map {
            case Some(Exit.Failure(_)) => false
            case _ => true
          }

          override def state: URIO[R2 with GreyhoundMetrics with Clock, DispatcherExposedState] = dispatcher.expose
        }
    }
  }

  private def pollOnce[R1, R2](running: Ref[Boolean],
                               consumer: Consumer[R1],
                               dispatcher: Dispatcher[R2],
                               paused: Ref[Set[TopicPartition]],
                               offsets: Offsets,
                               config: EventLoopConfig,
                               clientId: ClientId,
                               group: Group): URIO[R1 with R2 with GreyhoundMetrics, Boolean] =
    running.get.flatMap {
      case true =>
        for {
          _ <- resumePartitions(consumer, clientId, group, dispatcher, paused)
          _ <- pollAndHandle(consumer, dispatcher, paused, config, clientId)
          _ <- commitOffsets(consumer, offsets)
        } yield true

      case false => UIO(false)
    }

  private def resumePartitions[R1, R2](consumer: Consumer[R1],
                                       clientId: ClientId,
                                       group: Group,
                                       dispatcher: Dispatcher[R2],
                                       pausedRef: Ref[Set[TopicPartition]]) =
    for {
      paused <- pausedRef.get
      partitionsToResume <- dispatcher.resumeablePartitions(paused)
      _ <- ZIO.when(partitionsToResume.nonEmpty)(
        report(LowWatermarkReached(clientId, group, partitionsToResume)))
      _ <- consumer.resume(partitionsToResume)
        .tapError(e => UIO(e.printStackTrace())).ignore
      _ <- pausedRef.update(_ -- partitionsToResume)
    } yield ()

  private val emptyRecords = ZIO.succeed(ConsumerRecords.empty())

  private def pollAndHandle[R1, R2](consumer: Consumer[R1],
                                    dispatcher: Dispatcher[R2],
                                    pausedRef: Ref[Set[TopicPartition]],
                                    config: EventLoopConfig,
                                    clientId: ClientId) =
    consumer.poll(config.pollTimeout).catchAll(_ => emptyRecords).flatMap { records =>
      pausedRef.get.flatMap(paused =>
        ZIO.foldLeft(records.asScala)(paused) { (acc, kafkaRecord) =>
          val record = ConsumerRecord(kafkaRecord)
          val partition = TopicPartition(record)
          if (acc contains partition)
            report(PartitionThrottled(partition, record.offset)).as(acc)
          else
            dispatcher.submit(record).flatMap {
              case SubmitResult.Submitted => ZIO.succeed(acc)
              case SubmitResult.Rejected =>
                report(HighWatermarkReached(partition, record.offset)) *>
                  consumer.pause(record).fold(_ => acc, _ => acc + partition)
            }
        }.flatMap(pausedTopics => pausedRef.update(_ => pausedTopics)))
    }

  private def commitOffsets[R](consumer: Consumer[R],
                               offsets: Offsets,
                               calledOnRebalance: Boolean = false) =
    offsets.committable.flatMap { committable =>
      consumer.commit(committable, calledOnRebalance).catchAll { _ =>
        ZIO.foreach_(committable) {
          case (partition, offset) =>
            offsets.update(partition, offset)
        }
      }
    }

}

case class EventLoopConfig(pollTimeout: Duration,
                           drainTimeout: Duration,
                           lowWatermark: Int,
                           highWatermark: Int,
                           rebalanceListener: RebalanceListener[Any]) // TODO parametrize?

object EventLoopConfig {
  val Default = EventLoopConfig(
    pollTimeout = 500.millis,
    drainTimeout = 30.seconds,
    lowWatermark = 128,
    highWatermark = 256,
    rebalanceListener = RebalanceListener.Empty)
}

sealed trait EventLoopMetric extends GreyhoundMetric

object EventLoopMetric {

  case class StartingEventLoop(clientId: ClientId, group: Group) extends EventLoopMetric

  case class PausingEventLoop(clientId: ClientId, group: Group) extends EventLoopMetric

  case class ResumingEventLoop(clientId: ClientId, group: Group) extends EventLoopMetric

  case class StoppingEventLoop(clientId: ClientId, group: Group) extends EventLoopMetric

  case class DrainTimeoutExceeded(clientId: ClientId, group: Group) extends EventLoopMetric

  case class HighWatermarkReached(partition: TopicPartition, onOffset: Offset) extends EventLoopMetric

  case class PartitionThrottled(partition: TopicPartition, onOffset: Offset) extends EventLoopMetric

  case class LowWatermarkReached(clientId: ClientId, group: Group, partitionsToResume: Set[TopicPartition]) extends EventLoopMetric

}

sealed trait EventLoopState

object EventLoopState {

  case object Running extends EventLoopState

  case object Paused extends EventLoopState

  case object ShuttingDown extends EventLoopState

}

