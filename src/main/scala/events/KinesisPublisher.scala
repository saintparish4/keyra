package events

import scala.jdk.CollectionConverters.*

import org.typelevel.log4cats.Logger

import config.KinesisConfig
import cats.effect.std.Queue
import cats.effect.syntax.spawn.*
import cats.effect.{Async, Fiber, Resource}
import cats.syntax.all.*
import io.circe.syntax.*
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.*
import observability.MetricsPublisher

/** Kinesis-backed event publisher using a bounded circular-buffer queue and a
  * background drain fiber.
  *
  * ==Design==
  *   - `publish`/`publishBatch` enqueue events non-blocking into a
  *     `circularBuffer` queue; when the queue is full the oldest event is
  *     silently dropped (drop-oldest semantics).
  *   - A single background fiber dequeues events and publishes them to Kinesis.
  *     On transient failure the fiber retries once; if the retry also fails the
  *     event is dropped and a `DroppedKinesisEvent` metric is emitted.
  *   - The drain fiber is managed by `KinesisPublisher.resource` and is
  *     cancelled on Resource release.
  *
  * This replaces the previous fire-and-forget approach with bounded, observable
  * back-pressure: the queue size is configurable (`kinesis.queue-size`) and
  * dropped events are counted in CloudWatch.
  */
class KinesisPublisher[F[_]: Async: Logger](
    client: KinesisAsyncClient,
    config: KinesisConfig,
    queue: Queue[F, RateLimitEvent],
    metrics: MetricsPublisher[F],
) extends EventPublisher[F]:

  private val logger = Logger[F]

  override def publish(event: RateLimitEvent): F[Unit] =
    if !config.enabled then Async[F].unit else queue.offer(event)

  override def publishBatch(events: List[RateLimitEvent]): F[Unit] =
    if !config.enabled || events.isEmpty then Async[F].unit
    else events.traverse_(queue.offer)

  override def healthCheck: F[Either[String, Unit]] =
    val request = DescribeStreamSummaryRequest.builder()
      .streamName(config.streamName).build()
    Async[F].fromCompletableFuture(
      Async[F].delay(client.describeStreamSummary(request).toCompletableFuture),
    ).map(_ => Right(())).handleError(e => Left(e.getMessage))

  /** Background drain loop: take one event, publish it with single retry. Runs
    * forever until cancelled by Resource cleanup.
    */
  private[events] def drainLoop: F[Nothing] = queue.take
    .flatMap(publishWithRetry).foreverM

  private def publishWithRetry(event: RateLimitEvent): F[Unit] = publishDirect(
    event,
  ).handleErrorWith(e1 =>
    logger.warn(e1)(s"Kinesis publish failed (attempt 1), retrying: ${event
        .eventType}") *> publishDirect(event).handleErrorWith(e2 =>
      logger
        .error(e2)(s"Kinesis publish failed after retry, dropping event: ${event
            .eventType}") *> metrics.increment("DroppedKinesisEvent"),
    ),
  )

  private def publishDirect(event: RateLimitEvent): F[Unit] =
    val json = event.asJson.noSpaces
    val bytes = SdkBytes.fromUtf8String(json)
    val request = PutRecordRequest.builder().streamName(config.streamName)
      .partitionKey(event.partitionKey).data(bytes).build()
    Async[F].fromCompletableFuture(
      Async[F].delay(client.putRecord(request).toCompletableFuture),
    ).void

object KinesisPublisher:

  /** Create a `KinesisPublisher` wrapped in a `Resource` that manages the
    * background drain fiber lifecycle.
    *
    * The queue uses `circularBuffer` semantics: `offer` never blocks; if the
    * queue is full the oldest event is evicted to make room for the new one.
    */
  def resource[F[_]: Async: Logger](
      client: KinesisAsyncClient,
      config: KinesisConfig,
      metrics: MetricsPublisher[F],
  ): Resource[F, EventPublisher[F]] =
    for
      queue <- Resource
        .eval(Queue.circularBuffer[F, RateLimitEvent](config.queueSize))
      publisher = new KinesisPublisher[F](client, config, queue, metrics)
      _ <- Resource.make(publisher.drainLoop.start)(_.cancel)
    yield publisher
