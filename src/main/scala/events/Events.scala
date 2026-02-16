package events

import java.time.Instant

import cats.effect.*
import cats.syntax.all.*
import io.circe.*
import io.circe.generic.auto.*
import io.circe.syntax.*

/** Rate limit events for analytics and monitoring.
  */
sealed trait RateLimitEvent:
  def timestamp: Instant
  def eventType: String
  def partitionKey: String

object RateLimitEvent:
  /** Request was allowed within rate limit.
    */
  case class Allowed(
      timestamp: Instant,
      apiKey: String,
      clientId: String,
      endpoint: String,
      tokensRemaining: Int,
      cost: Int,
      tier: String,
  ) extends RateLimitEvent:
    val eventType = "rate_limit_allowed"
    val partitionKey = apiKey

  /** Request was rejected due to rate limit exceeded.
    */
  case class Rejected(
      timestamp: Instant,
      apiKey: String,
      clientId: String,
      endpoint: String,
      retryAfterSeconds: Int,
      reason: String,
      tier: String,
  ) extends RateLimitEvent:
    val eventType = "rate_limit_rejected"
    val partitionKey = apiKey

  /** Idempotency key hit (duplicate detected).
    */
  case class IdempotencyHit(
      timestamp: Instant,
      idempotencyKey: String,
      clientId: String,
      originalRequestTime: Instant,
  ) extends RateLimitEvent:
    val eventType = "idempotency_hit"
    val partitionKey = clientId

  /** New idempotency key registered.
    */
  case class IdempotencyNew(
      timestamp: Instant,
      idempotencyKey: String,
      clientId: String,
      ttlSeconds: Long,
  ) extends RateLimitEvent:
    val eventType = "idempotency_new"
    val partitionKey = clientId

  /** Circuit breaker state change.
    */
  case class CircuitBreakerStateChange(
      timestamp: Instant,
      name: String,
      previousState: String,
      newState: String,
      failureCount: Int,
  ) extends RateLimitEvent:
    val eventType = "circuit_breaker_state_change"
    val partitionKey = name

  /** Degraded mode activated/deactivated.
    */
  case class DegradedModeChange(
      timestamp: Instant,
      service: String,
      degraded: Boolean,
      reason: String,
  ) extends RateLimitEvent:
    val eventType = "degraded_mode_change"
    val partitionKey = service

  // JSON encoder for events
  given Encoder[RateLimitEvent] = Encoder.instance {
    case e: Allowed => e.asJson
        .deepMerge(Json.obj("event_type" -> Json.fromString(e.eventType)))
    case e: Rejected => e.asJson
        .deepMerge(Json.obj("event_type" -> Json.fromString(e.eventType)))
    case e: IdempotencyHit => e.asJson
        .deepMerge(Json.obj("event_type" -> Json.fromString(e.eventType)))
    case e: IdempotencyNew => e.asJson
        .deepMerge(Json.obj("event_type" -> Json.fromString(e.eventType)))
    case e: CircuitBreakerStateChange => e.asJson
        .deepMerge(Json.obj("event_type" -> Json.fromString(e.eventType)))
    case e: DegradedModeChange => e.asJson
        .deepMerge(Json.obj("event_type" -> Json.fromString(e.eventType)))
  }
