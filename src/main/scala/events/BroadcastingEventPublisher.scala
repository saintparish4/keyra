package events

import cats.effect.*
import cats.effect.std.Queue
import cats.syntax.all.*

/** Wraps an underlying EventPublisher and also offers each event to a bounded queue
  * for dashboard SSE. Queue offer is best-effort (tryOffer) so a full queue never
  * blocks the main publish path.
  */
final class BroadcastingEventPublisher[F[_]: Async](
    underlying: EventPublisher[F],
    dashboardQueue: Queue[F, RateLimitEvent],
) extends EventPublisher[F]:

  override def publish(event: RateLimitEvent): F[Unit] =
    underlying.publish(event) >> dashboardQueue.tryOffer(event).void

  override def publishBatch(events: List[RateLimitEvent]): F[Unit] =
    underlying.publishBatch(events) >> events.traverse_(e => dashboardQueue.tryOffer(e).void)

  override def healthCheck: F[Boolean] =
    underlying.healthCheck
