package wiring

import org.typelevel.log4cats.Logger

import cats.effect.*
import cats.effect.std.Queue
import cats.syntax.all.*
import config.AppConfig
import events.RateLimitEvent
import observability.{MetricsConfig, MetricsPublisher, PrometheusMetrics}

case class ObservabilityModule[F[_]](
    metricsPublisher: MetricsPublisher[F],
    promMetrics: Option[PrometheusMetrics[F]],
    dashboardQueue: Queue[F, RateLimitEvent],
)

object ObservabilityModule:
  def resource[F[_]: Async: Logger](
      config: AppConfig,
  ): Resource[F, ObservabilityModule[F]] =
    for
      basePublisher <- config.metrics.enabled match
        case true =>
          val metricsConfig = MetricsConfig(
            namespace = config.metrics.namespace,
            maxBufferSize = config.metrics.maxBufferSize,
            flushThreshold = config.metrics.flushThreshold,
          )
          MetricsPublisher.cloudWatch[F](config.aws.region, metricsConfig)
        case false => Resource
            .pure[F, MetricsPublisher[F]](MetricsPublisher.noop[F])

      promMetrics <- config.prometheus.enabled match
        case true => Resource.eval(PrometheusMetrics[F].map(Some(_)))
        case false => Resource.pure[F, Option[PrometheusMetrics[F]]](None)

      metricsPublisher = promMetrics match
        case Some(prom) => PrometheusMetrics.dual[F](basePublisher, prom)
        case None => basePublisher

      dashboardQueue <- Resource.eval(Queue.bounded[F, RateLimitEvent](512))
    yield ObservabilityModule(metricsPublisher, promMetrics, dashboardQueue)
