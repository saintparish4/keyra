package storage

import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

import org.typelevel.log4cats.Logger

import cats.effect.*
import cats.syntax.all.*
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import core.{
  RateLimitDecision, RateLimitProfile, RateLimitStore, SlidingWindow,
  SlidingWindowState,
}
import observability.MetricsPublisher
import resilience.{OCCConflictException, Retry, RetryPolicy}
import DynamoDBOps.*

/** DynamoDB-backed sliding window rate limit store.
  *
  * All sub-window counts for a key are stored in a **single item** so that OCC
  * operates atomically on the complete window state -- no cross-item races.
  *
  * ==DynamoDB schema==
  * Uses the same table as the token-bucket store; keys are namespaced with
  * "sw#" to avoid collision.
  *
  * pk (S): "sw#{clientKey}" counts (M): map of sub_window_start_ms_string ->
  * count_string version (N): OCC version; starts at 0 (first write uses
  * attribute_not_exists(pk)) ttl (N): epoch-seconds for DynamoDB TTL
  * auto-expiry
  *
  * ==RateLimitProfile reuse==
  * capacity = max requests per parent window ttlSeconds = parent window
  * duration in seconds refillRatePerSecond = unused
  *
  * ==OCC strategy==
  * Mirrors DynamoDBRateLimitStore: RetryPolicy.occRetry (10 retries, 1 ms base,
  * 1.5x backoff, 20% jitter). On exhaustion the request is rejected rather than
  * over-admitted.
  *
  * ==Corrupt-state handling==
  * parseState returns Either[String, SlidingWindowState]; corrupt items are
  * logged, metered (CorruptStateRead), and treated as empty state (fail-open on
  * corrupt rather than blocking all traffic).
  */
class DynamoDBSlidingWindowStore[F[_]: Async: Logger](
    client: DynamoDbAsyncClient,
    tableName: String,
    metrics: MetricsPublisher[F],
    subWindowCount: Int = SlidingWindow.DefaultSubWindowCount,
) extends RateLimitStore[F]:

  private val logger = Logger[F]
  private val retryPolicy = RetryPolicy.occRetry

  override def checkAndConsume(
      key: String,
      cost: Int,
      profile: RateLimitProfile,
  ): F[RateLimitDecision] = Retry
    .retryWithTracking(retryPolicy, s"SW-OCC-checkAndConsume($key)")(
      singleAttempt(key, cost, profile),
    ).flatMap(r =>
      metrics.gauge("SlidingWindowOCCAttempts", r.attempts.toDouble).as(r.result),
    ).handleErrorWith { case _: OCCConflictException =>
      // OCC exhausted: reject conservatively rather than over-admit.
      Clock[F].realTime.map(_.toMillis).map { nowMs =>
        val windowMs = profile.ttlSeconds * 1000L
        val active = SlidingWindow
          .activeSubWindowStarts(nowMs, windowMs, subWindowCount)
        val reset = SlidingWindow.resetAt(Map.empty, active, windowMs)
        RateLimitDecision
          .Rejected(SlidingWindow.retryAfterSeconds(reset, nowMs), reset)
      }
    }

  /** Single OCC attempt: read → compute → conditional write. */
  private def singleAttempt(
      key: String,
      cost: Int,
      profile: RateLimitProfile,
  ): F[RateLimitDecision] =
    val windowMs = profile.ttlSeconds * 1000L
    for
      nowMs <- Clock[F].realTime.map(_.toMillis)
      current <- getOrInitState(key, profile, nowMs, windowMs)
      active = SlidingWindow
        .activeSubWindowStarts(nowMs, windowMs, subWindowCount)
      total = SlidingWindow.totalCount(current.counts, active)
      decision <-
        if total + cost > profile.capacity then
          val reset = SlidingWindow.resetAt(current.counts, active, windowMs)
          Async[F].pure(
            RateLimitDecision
              .Rejected(SlidingWindow.retryAfterSeconds(reset, nowMs), reset),
          )
        else
          val curSw = active.head
          val updated = current.counts
            .updated(curSw, current.counts.getOrElse(curSw, 0L) + cost)
          val pruned = SlidingWindow.pruneStale(updated, active)
          val newState = SlidingWindowState(pruned, current.version + 1)
          attemptWrite(key, current.version, newState, profile.ttlSeconds, nowMs)
            .flatMap {
              case true =>
                val newTotal = total + cost
                val reset = SlidingWindow.resetAt(pruned, active, windowMs)
                Async[F].pure(RateLimitDecision.Allowed(
                  SlidingWindow.remaining(profile.capacity, newTotal),
                  reset,
                ))
              case false => Async[F]
                  .raiseError(OCCConflictException(key, current.version.toInt))
            }
    yield decision

  override def getStatus(
      key: String,
      profile: RateLimitProfile,
  ): F[Option[RateLimitDecision.Allowed]] =
    val windowMs = profile.ttlSeconds * 1000L
    for
      nowMs <- Clock[F].realTime.map(_.toMillis)
      item <- getState(key)
      result <- item match
        case Some(Right(state)) =>
          val active = SlidingWindow
            .activeSubWindowStarts(nowMs, windowMs, subWindowCount)
          val total = SlidingWindow.totalCount(state.counts, active)
          val reset = SlidingWindow.resetAt(state.counts, active, windowMs)
          Async[F].pure(Some(
            RateLimitDecision
              .Allowed(SlidingWindow.remaining(profile.capacity, total), reset),
          ))
        case Some(Left(err)) => logger.error(
            s"Corrupt sliding-window state key=$key: $err — returning no status",
          ) *> metrics.increment("CorruptStateRead").as(None)
        case None => Async[F].pure(None)
    yield result

  override def healthCheck: F[Either[String, Unit]] =
    dynamoHealthCheck(client, tableName)

  // ---------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------

  private def getOrInitState(
      key: String,
      profile: RateLimitProfile,
      nowMs: Long,
      windowMs: Long,
  ): F[SlidingWindowState] = getState(key).flatMap {
    case Some(Right(state)) => Async[F].pure(state)
    case Some(Left(err)) =>
      // Corrupt: fail-open (empty counts, version 0) so traffic is not blocked.
      logger
        .error(s"Corrupt sliding-window state key=$key: $err — failing open") *>
        metrics.increment("CorruptStateRead") *>
        Async[F].pure(SlidingWindowState(Map.empty, 0L))
    case None => Async[F].pure(SlidingWindowState(Map.empty, 0L))
  }

  private def getState(
      key: String,
  ): F[Option[Either[String, SlidingWindowState]]] =
    val request = GetItemRequest.builder().tableName(tableName)
      .key(Map("pk" -> attr(s"sw#$key")).asJava).consistentRead(true).build()

    Async[F].fromCompletableFuture(
      Async[F].delay(client.getItem(request).toCompletableFuture),
    ).map(response =>
      if response.hasItem && !response.item().isEmpty then
        Some(parseState(response.item().asScala.toMap))
      else None,
    )

  /** Parses a DynamoDB item into SlidingWindowState. Returns Left(reason) on
    * any missing/malformed attribute so callers can log and metric the error
    * without propagating exceptions.
    */
  private def parseState(
      item: Map[String, AttributeValue],
  ): Either[String, SlidingWindowState] =
    try
      val versionE = item.get("version").toRight("missing 'version' attribute")
        .map(_.n().toLong)
      val countsE = item.get("counts").toRight("missing 'counts' attribute")
        .flatMap(av =>
          if !av.hasM then Left("'counts' is not a Map attribute")
          else
            Right(av.m().asScala.toMap.map { case (k, v) =>
              k.toLong -> v.n().toLong
            }),
        )
      for
        version <- versionE
        counts <- countsE
      yield SlidingWindowState(counts, version)
    catch
      case e: NumberFormatException =>
        Left(s"malformed numeric attribute: ${e.getMessage}")

  private def attemptWrite(
      key: String,
      expectedVersion: Long,
      newState: SlidingWindowState,
      ttlSeconds: Long,
      nowMs: Long,
  ): F[Boolean] =
    val ttl = nowMs / 1000 + ttlSeconds
    val countsMap = newState.counts.map { case (sw, count) =>
      sw.toString -> attrN(count)
    }.asJava

    val item = Map(
      "pk" -> attr(s"sw#$key"),
      "counts" -> AttributeValue.builder().m(countsMap).build(),
      "version" -> attrN(newState.version),
      "ttl" -> attrN(ttl),
    ).asJava

    val builder = PutItemRequest.builder().tableName(tableName).item(item)
    // First write: attribute_not_exists guard; subsequent: version matches.
    val request =
      if expectedVersion == 0L then
        builder.conditionExpression("attribute_not_exists(pk)").build()
      else
        builder.conditionExpression("version = :expected")
          .expressionAttributeValues(
            Map(":expected" -> attrN(expectedVersion)).asJava,
          ).build()

    conditionalPut(client, request)

object DynamoDBSlidingWindowStore:
  def apply[F[_]: Async: Logger](
      client: DynamoDbAsyncClient,
      tableName: String,
      metrics: MetricsPublisher[F],
      subWindowCount: Int = SlidingWindow.DefaultSubWindowCount,
  ): DynamoDBSlidingWindowStore[F] =
    new DynamoDBSlidingWindowStore[F](client, tableName, metrics, subWindowCount)
