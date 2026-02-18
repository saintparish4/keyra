package core

import java.time.Instant

/** Pure token-bucket state and computation module.
  *
  * This is the canonical implementation of the token-bucket algorithm used by
  * both in-memory and DynamoDB rate-limit stores. All refill/consume logic
  * lives here; stores delegate to these functions to avoid duplication.
  *
  * ==Refill formula==
  * {{{
  *   elapsed_sec  = (nowMs - state.lastRefillMs) / 1000.0
  *   tokensToAdd  = elapsed_sec × profile.refillRatePerSecond
  *   refilled     = min(capacity, state.tokens + tokensToAdd)
  * }}}
  *
  * ==Invariants==
  *   - tokens ∈ [0, capacity] at all times.
  *   - version increments by 1 on every successful [[consume]]; unchanged by
  *     [[refill]].
  *   - lastRefillMs is set to nowMs on a successful consume (not on refill).
  *   - cost must be ≤ capacity for [[consume]] to ever return Some.
  *
  * @see
  *   [[storage.DynamoDBRateLimitStore]] for OCC write semantics built on top of
  *   these functions.
  */

case class TokenBucketState(tokens: Double, lastRefillMs: Long, version: Long):
  def tokensInt: Int = tokens.toInt

object TokenBucket:

  /** Apply time-based token refill to a bucket state.
    *
    * Does not modify lastRefillMs or version — those are updated only on a
    * successful [[consume]].
    */
  def refill(
      state: TokenBucketState,
      nowMs: Long,
      profile: RateLimitProfile,
  ): TokenBucketState =
    val elapsed = (nowMs - state.lastRefillMs) / 1000.0
    val refilled = math.min(
      profile.capacity.toDouble,
      state.tokens + elapsed * profile.refillRatePerSecond,
    )
    state.copy(tokens = refilled)

  /** Attempt to consume `cost` tokens from an already-refilled bucket.
    *
    * Returns `Some(newState)` if there are enough tokens; `None` if
    * insufficient. On success, sets `lastRefillMs = nowMs` and increments
    * `version`.
    */
  def consume(
      state: TokenBucketState,
      cost: Int,
      nowMs: Long,
  ): Option[TokenBucketState] =
    if state.tokens >= cost then
      Some(TokenBucketState(state.tokens - cost, nowMs, state.version + 1))
    else None

  /** Instant at which the bucket will be fully replenished. */
  def resetAt(nowMs: Long, tokens: Double, profile: RateLimitProfile): Instant =
    val tokensToFull = profile.capacity - tokens
    val secondsToFull = (tokensToFull / profile.refillRatePerSecond).ceil.toLong
    Instant.ofEpochMilli(nowMs + secondsToFull * 1000)

  /** Seconds a caller must wait before `cost` tokens will be available. */
  def retryAfterSeconds(
      cost: Int,
      tokens: Double,
      profile: RateLimitProfile,
  ): Int = math.ceil((cost - tokens) / profile.refillRatePerSecond).toInt.max(1)
