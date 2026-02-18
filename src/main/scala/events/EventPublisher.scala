package events

/** Trait for publishing rate limit events
  *
  * Events are published asynchronously and failures should not affect the rate
  * limit decision returned to clients
  */
trait EventPublisher[F[_]]:
  // Publish a single event
  def publish(event: RateLimitEvent): F[Unit]

  // Publish a batch of events for efficiency
  def publishBatch(events: List[RateLimitEvent]): F[Unit]

  /** Health check for the event publisher.
    *
    * @return
    *   `Right(())` if healthy; `Left(reason)` with a human-readable description
    *   of the failure. Never raises an exception — failures are encoded in the
    *   return type.
    */
  def healthCheck: F[Either[String, Unit]]

object EventPublisher:
  // No-op publisher for when events are disabled
  def noop[F[_]](using F: cats.Applicative[F]): EventPublisher[F] =
    new EventPublisher[F]:
      def publish(event: RateLimitEvent): F[Unit] = F.unit
      def publishBatch(events: List[RateLimitEvent]): F[Unit] = F.unit
      def healthCheck: F[Either[String, Unit]] = F.pure(Right(()))
