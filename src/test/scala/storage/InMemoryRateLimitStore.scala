package storage

import cats.effect.{Clock, Ref, Sync}
import cats.syntax.all.*
import core.{RateLimitDecision, RateLimitProfile, RateLimitStore, TokenBucket, TokenBucketState}

/** In-memory rate limit store for testing purposes.
  *
  * Uses Ref for thread-safe state management, ensuring atomic operations
  * without external dependencies. Delegates token-bucket math to
  * [[core.TokenBucket]].
  */
class InMemoryRateLimitStore[F[_]: Sync](
    stateRef: Ref[F, Map[String, TokenBucketState]],
) extends RateLimitStore[F]:

  override def checkAndConsume(
      key: String,
      cost: Int,
      profile: RateLimitProfile,
  ): F[RateLimitDecision] =
    for
      nowMs <- Clock[F].realTime.map(_.toMillis)
      decision <- stateRef.modify { stateMap =>
        val current  = stateMap.getOrElse(key, TokenBucketState(profile.capacity.toDouble, nowMs, 0L))
        val refilled = TokenBucket.refill(current, nowMs, profile)

        TokenBucket.consume(refilled, cost, nowMs) match
          case Some(newState) =>
            val resetAt = TokenBucket.resetAt(nowMs, newState.tokens, profile)
            (stateMap.updated(key, newState), RateLimitDecision.Allowed(newState.tokensInt, resetAt))
          case None =>
            val retryAfter = TokenBucket.retryAfterSeconds(cost, refilled.tokens, profile)
            val resetAt    = TokenBucket.resetAt(nowMs, refilled.tokens, profile)
            (stateMap, RateLimitDecision.Rejected(retryAfter, resetAt))
      }
    yield decision

  override def getStatus(
      key: String,
      profile: RateLimitProfile,
  ): F[Option[RateLimitDecision.Allowed]] =
    for
      nowMs    <- Clock[F].realTime.map(_.toMillis)
      stateMap <- stateRef.get
    yield stateMap.get(key).map { state =>
      val refilled = TokenBucket.refill(state, nowMs, profile)
      val resetAt  = TokenBucket.resetAt(nowMs, refilled.tokens, profile)
      RateLimitDecision.Allowed(refilled.tokensInt, resetAt)
    }

  override def healthCheck: F[Either[String, Unit]] = Sync[F].pure(Right(()))

object InMemoryRateLimitStore:
  def create[F[_]: Sync]: F[InMemoryRateLimitStore[F]] =
    Ref.of[F, Map[String, TokenBucketState]](Map.empty).map(new InMemoryRateLimitStore[F](_))