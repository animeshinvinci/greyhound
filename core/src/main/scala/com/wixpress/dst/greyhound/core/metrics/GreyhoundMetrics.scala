package com.wixpress.dst.greyhound.core.metrics

import org.slf4j.LoggerFactory
import zio.{Has, UIO, URIO, ZIO, ZLayer}

object GreyhoundMetrics {
  trait Service {
    def report(metric: GreyhoundMetric): UIO[Unit]
  }

  def report(metric: GreyhoundMetric): URIO[GreyhoundMetrics, Unit] =
    ZIO.accessM(_.get.report(metric))

  object Service {
    val Live = {
      val logger = LoggerFactory.getLogger("metrics")
      fromReporter(metric => logger.info(metric.toString))
    }
  }
  val liveLayer = ZLayer.succeed(Service.Live)
  val live = Has(Service.Live)

  def fromReporter(report: GreyhoundMetric => Unit): GreyhoundMetrics.Service =
    metric => ZIO.effectTotal(report(metric))

}

trait GreyhoundMetric extends Product with Serializable

