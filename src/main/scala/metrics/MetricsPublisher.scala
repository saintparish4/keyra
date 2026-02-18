package metrics

import java.time.Instant

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.typelevel.log4cats.Logger

import cats.effect.*
import cats.effect.syntax.spawn.*
import cats.syntax.all.*
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.model.*

/** Production-grade metrics publisher for CloudWatch.
  *
  * Provides:
  *   - Rate limit decision metrics
  *   - Latency histograms
  *   - Circuit breaker state metrics
  *   - Cache hit/miss ratios
  *   - Error tracking
  *   - Custom business metrics
  */

/** Metrics configuration.
  */
case class MetricsConfig(
    namespace: String = "RateLimiter",
    environment: String = "dev",
    flushInterval: FiniteDuration = 60.seconds,
    batchSize: Int = 20,
    enabled: Boolean = true,
    highResolution: Boolean = false, // 1-second resolution costs extra
    maxBufferSize: Int = 50000, // oldest entries dropped when buffer is full
    flushThreshold: Int = 1000, // trigger an async flush when buffer reaches this size
)

object MetricsConfig:
  val default: MetricsConfig = MetricsConfig()

  val production: MetricsConfig = MetricsConfig(
    namespace = "RateLimiter/Production",
    flushInterval = 30.seconds,
    highResolution = true,
  )

/** Metric data point.
  */
case class MetricDataPoint(
    name: String,
    value: Double,
    unit: StandardUnit,
    dimensions: Map[String, String] = Map.empty,
    timestamp: Option[Instant] = None,
)

/** Metrics publisher trait.
  */
trait MetricsPublisher[F[_]]:
  /** Record a counter increment */
  def increment(
      name: String,
      dimensions: Map[String, String] = Map.empty,
  ): F[Unit]

  /** Record a gauge value */
  def gauge(
      name: String,
      value: Double,
      dimensions: Map[String, String] = Map.empty,
  ): F[Unit]

  /** Record a latency measurement */
  def recordLatency(
      name: String,
      latencyMs: Double,
      dimensions: Map[String, String] = Map.empty,
  ): F[Unit]

  /** Time an operation and record latency */
  def timed[A](name: String, dimensions: Map[String, String] = Map.empty)(
      fa: F[A],
  ): F[A]

  /** Record a rate limit decision */
  def recordRateLimitDecision(
      allowed: Boolean,
      clientId: String,
      tier: String = "unknown",
  ): F[Unit]

  /** Record circuit breaker state change */
  def recordCircuitBreakerState(
      name: String,
      state: String,
      failureCount: Int,
  ): F[Unit]

  /** Record cache metrics */
  def recordCacheMetrics(cacheName: String, hitRate: Double, size: Long): F[Unit]

  /** Record degraded mode operation */
  def recordDegradedOperation(operation: String): F[Unit]

  /** Flush pending metrics */
  def flush: F[Unit]

object MetricsPublisher:

  /** Create a CloudWatch metrics publisher with explicit client.
    */
  def cloudWatch[F[_]: Async: Logger](
      client: CloudWatchAsyncClient,
      config: MetricsConfig,
  ): Resource[F, MetricsPublisher[F]] =
    val logger = Logger[F]
    for
      bufferRef <- Resource.eval(Ref.of[F, List[MetricDataPoint]](List.empty))
      lastFlushRef <- Resource.eval(Ref.of[F, Long](System.currentTimeMillis()))
      _ <-
        if config.enabled then
          // Background periodic flush fiber; on shutdown, cancel then flush remaining
          Resource.make(flushLoop(bufferRef, lastFlushRef, client, config).start)(
            fiber =>
              fiber.cancel *>
                doFlush(bufferRef, lastFlushRef, client, config, logger),
          )
        else Resource.pure[F, Fiber[F, Throwable, Nothing]](null)
    yield new CloudWatchMetricsPublisher[F](
      client,
      config,
      bufferRef,
      lastFlushRef,
    )

  /** Create a CloudWatch metrics publisher with default client and explicit
    * config. Creates and manages the CloudWatch client internally.
    */
  def cloudWatch[F[_]: Async: Logger](
      region: String,
      config: MetricsConfig,
  ): Resource[F, MetricsPublisher[F]] =
    import software.amazon.awssdk.regions.Region
    import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider

    val clientResource = Resource.make(Async[F].delay(
      CloudWatchAsyncClient.builder().region(Region.of(region))
        .credentialsProvider(DefaultCredentialsProvider.create()).build(),
    ))(client => Async[F].delay(client.close()))

    clientResource.flatMap(client => cloudWatch(client, config))

  /** Create a CloudWatch metrics publisher using only region and namespace.
    * Keeps backward compatibility; uses all other defaults.
    */
  def cloudWatch[F[_]: Async: Logger](
      region: String,
      namespace: String,
  ): Resource[F, MetricsPublisher[F]] =
    cloudWatch(region, MetricsConfig(namespace = namespace))

  /** Create a no-op metrics publisher (for testing).
    */
  def noop[F[_]: Async]: MetricsPublisher[F] = new MetricsPublisher[F]:
    override def increment(
        name: String,
        dimensions: Map[String, String],
    ): F[Unit] = Async[F].unit
    override def gauge(
        name: String,
        value: Double,
        dimensions: Map[String, String],
    ): F[Unit] = Async[F].unit
    override def recordLatency(
        name: String,
        latencyMs: Double,
        dimensions: Map[String, String],
    ): F[Unit] = Async[F].unit
    override def timed[A](name: String, dimensions: Map[String, String])(
        fa: F[A],
    ): F[A] = fa
    override def recordRateLimitDecision(
        allowed: Boolean,
        clientId: String,
        tier: String,
    ): F[Unit] = Async[F].unit
    override def recordCircuitBreakerState(
        name: String,
        state: String,
        failureCount: Int,
    ): F[Unit] = Async[F].unit
    override def recordCacheMetrics(
        cacheName: String,
        hitRate: Double,
        size: Long,
    ): F[Unit] = Async[F].unit
    override def recordDegradedOperation(operation: String): F[Unit] = Async[F]
      .unit
    override def flush: F[Unit] = Async[F].unit

  private def flushLoop[F[_]: Async: Logger](
      bufferRef: Ref[F, List[MetricDataPoint]],
      lastFlushRef: Ref[F, Long],
      client: CloudWatchAsyncClient,
      config: MetricsConfig,
  ): F[Nothing] =
    val logger = Logger[F]
    (Async[F].sleep(config.flushInterval) *>
      doFlush(bufferRef, lastFlushRef, client, config, logger)).foreverM

  def doFlush[F[_]: Async](
      bufferRef: Ref[F, List[MetricDataPoint]],
      lastFlushRef: Ref[F, Long],
      client: CloudWatchAsyncClient,
      config: MetricsConfig,
      logger: Logger[F],
  ): F[Unit] =
    for
      metrics <- bufferRef.getAndSet(List.empty)
      _ <-
        if metrics.nonEmpty then
          publishMetrics(client, config, metrics, logger) *>
            lastFlushRef.set(System.currentTimeMillis())
        else Async[F].unit
    yield ()

  private def publishMetrics[F[_]: Async](
      client: CloudWatchAsyncClient,
      config: MetricsConfig,
      metrics: List[MetricDataPoint],
      logger: Logger[F],
  ): F[Unit] =
    val metricData = metrics.map { point =>
      val builder = MetricDatum.builder().metricName(point.name)
        .value(point.value).unit(point.unit)
        .timestamp(point.timestamp.getOrElse(Instant.now()))

      val dims = (point.dimensions + ("Environment" -> config.environment))
        .map { case (k, v) => Dimension.builder().name(k).value(v).build() }
        .toList

      if dims.nonEmpty then builder.dimensions(dims.asJava)

      if config.highResolution then builder.storageResolution(1) // 1-second resolution

      builder.build()
    }

    // Batch into groups of 20 (CloudWatch limit)
    val batches = metricData.grouped(config.batchSize).toList

    batches.traverse_ { batch =>
      val request = PutMetricDataRequest.builder().namespace(config.namespace)
        .metricData(batch.asJava).build()

      Async[F].fromCompletableFuture(Async[F].delay(client.putMetricData(request)))
        .void.handleErrorWith(error =>
          logger.error(error)("Failed to publish metrics to CloudWatch"),
        )
    }

/** CloudWatch metrics publisher implementation.
  */
private class CloudWatchMetricsPublisher[F[_]: Async: Logger](
    client: CloudWatchAsyncClient,
    config: MetricsConfig,
    bufferRef: Ref[F, List[MetricDataPoint]],
    lastFlushRef: Ref[F, Long],
) extends MetricsPublisher[F]:

  private val logger = Logger[F]

  override def increment(
      name: String,
      dimensions: Map[String, String],
  ): F[Unit] =
    addMetric(MetricDataPoint(name, 1.0, StandardUnit.COUNT, dimensions))

  override def gauge(
      name: String,
      value: Double,
      dimensions: Map[String, String],
  ): F[Unit] =
    addMetric(MetricDataPoint(name, value, StandardUnit.NONE, dimensions))

  override def recordLatency(
      name: String,
      latencyMs: Double,
      dimensions: Map[String, String],
  ): F[Unit] = addMetric(
    MetricDataPoint(name, latencyMs, StandardUnit.MILLISECONDS, dimensions),
  )

  override def timed[A](name: String, dimensions: Map[String, String])(
      fa: F[A],
  ): F[A] =
    for
      start <- Clock[F].monotonic
      result <- fa
      end <- Clock[F].monotonic
      latencyMs = (end - start).toMillis.toDouble
      _ <- recordLatency(name, latencyMs, dimensions)
    yield result

  override def recordRateLimitDecision(
      allowed: Boolean,
      clientId: String,
      tier: String,
  ): F[Unit] =
    val metricName = if allowed then "RateLimitAllowed" else "RateLimitRejected"
    val dims = Map(
      "ClientTier" -> tier,
      "Decision" -> (if allowed then "allowed" else "rejected"),
    )
    increment(metricName, dims) *> increment("RateLimitDecisions", dims)

  override def recordCircuitBreakerState(
      name: String,
      state: String,
      failureCount: Int,
  ): F[Unit] =
    val dims = Map("CircuitBreaker" -> name, "State" -> state)
    gauge("CircuitBreakerState", stateToValue(state), dims) *>
      gauge("CircuitBreakerFailures", failureCount.toDouble, dims)

  override def recordCacheMetrics(
      cacheName: String,
      hitRate: Double,
      size: Long,
  ): F[Unit] =
    val dims = Map("CacheName" -> cacheName)
    gauge("CacheHitRate", hitRate * 100, dims) *> // Percentage
      gauge("CacheSize", size.toDouble, dims)

  override def recordDegradedOperation(operation: String): F[Unit] =
    increment("DegradedOperation", Map("Operation" -> operation))

  override def flush: F[Unit] = MetricsPublisher
    .doFlush(bufferRef, lastFlushRef, client, config, logger)

  private def addMetric(metric: MetricDataPoint): F[Unit] =
    if config.enabled then
      bufferRef.update { buf =>
        // Buffer is newest-first; tail is the oldest entry — drop it when full
        val trimmed =
          if buf.size >= config.maxBufferSize then buf.dropRight(1) else buf
        metric :: trimmed
      }.flatMap(_ => triggerFlushIfNeeded)
    else Async[F].unit

  private def triggerFlushIfNeeded: F[Unit] = bufferRef.get.flatMap(buf =>
    // When the buffer reaches the flush threshold, start an async flush fiber
    // so metrics are not held in memory until the next periodic flush cycle.
    if buf.size >= config.flushThreshold then
      MetricsPublisher.doFlush(bufferRef, lastFlushRef, client, config, logger)
        .start.void
    else Async[F].unit,
  )

  private def stateToValue(state: String): Double = state.toLowerCase match
    case "closed" => 0.0
    case "halfopen" | "half_open" => 0.5
    case "open" => 1.0
    case _ => -1.0

/** Structured logging metrics for when CloudWatch is unavailable.
  */
object LoggingMetrics:
  def apply[F[_]: Async: Logger]: F[MetricsPublisher[F]] =
    val logger = Logger[F]

    Async[F].pure {
      new MetricsPublisher[F]:
        override def increment(
            name: String,
            dimensions: Map[String, String],
        ): F[Unit] = logger.info(s"METRIC: $name +1 ${formatDims(dimensions)}")

        override def gauge(
            name: String,
            value: Double,
            dimensions: Map[String, String],
        ): F[Unit] = logger
          .info(s"METRIC: $name = $value ${formatDims(dimensions)}")

        override def recordLatency(
            name: String,
            latencyMs: Double,
            dimensions: Map[String, String],
        ): F[Unit] = logger.info(
          s"METRIC: $name latency=${latencyMs}ms ${formatDims(dimensions)}",
        )

        override def timed[A](name: String, dimensions: Map[String, String])(
            fa: F[A],
        ): F[A] =
          for
            start <- Clock[F].monotonic
            result <- fa
            end <- Clock[F].monotonic
            latencyMs = (end - start).toMillis.toDouble
            _ <- recordLatency(name, latencyMs, dimensions)
          yield result

        override def recordRateLimitDecision(
            allowed: Boolean,
            clientId: String,
            tier: String,
        ): F[Unit] = logger.info(s"METRIC: RateLimit decision=${
            if allowed then "allowed" else "rejected"
          } client=$clientId tier=$tier")

        override def recordCircuitBreakerState(
            name: String,
            state: String,
            failureCount: Int,
        ): F[Unit] = logger.info(
          s"METRIC: CircuitBreaker name=$name state=$state failures=$failureCount",
        )

        override def recordCacheMetrics(
            cacheName: String,
            hitRate: Double,
            size: Long,
        ): F[Unit] = logger
          .info(s"METRIC: Cache name=$cacheName hitRate=${hitRate *
              100}% size=$size")

        override def recordDegradedOperation(operation: String): F[Unit] =
          logger.warn(s"METRIC: DegradedOperation operation=$operation")

        override def flush: F[Unit] = Async[F].unit

        private def formatDims(dims: Map[String, String]): String =
          if dims.isEmpty then ""
          else dims.map { case (k, v) => s"$k=$v" }.mkString("[", ", ", "]")
    }
