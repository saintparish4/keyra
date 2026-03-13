package storage

import java.time.Instant

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

import org.typelevel.log4cats.Logger

import cats.effect.*
import cats.syntax.all.*
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import core.{RateLimitDecision, RateLimitProfile, RateLimitStore}
import _root_.metrics.MetricsPublisher
import resilience.{OCCConflictException, Retry, RetryPolicy}
import DynamoDBOps.*

/** DynamoDB implementation of RateLimitStore using leaky bucket algorithm.
  *
  * State per key: level (current "water"), lastLeakMs, version. Leak:
  * leakAmount = (now_ms - lastLeakMs) / 1000.0 * leakRatePerSecond newLevel =
  * max(0, level - leakAmount) Allow if newLevel + cost <= capacity; then level =
  * newLevel + cost, lastLeakMs = now. Reject with resetAt = now + (newLevel +
  * cost - capacity) / leakRatePerSecond seconds.
  *
  * Profile: Reuses RateLimitProfile — capacity = bucket size,
  * refillRatePerSecond = leak rate. DynamoDB: Same schema as token bucket (pk,
  * tokens→level, lastRefillMs→lastLeakMs, version, ttl); OCC.
  */
class LeakyBucketRateLimitStore[F[_]: Async: Logger](
    client: DynamoDbAsyncClient,
    tableName: String,
    metrics: MetricsPublisher[F],
    retryPolicy: RetryPolicy = RetryPolicy.occRetry,
) extends RateLimitStore[F]:

  private val logger = Logger[F]

  override def checkAndConsume(
      key: String,
      cost: Int,
      profile: RateLimitProfile,
  ): F[RateLimitDecision] = Retry
    .retryWithTracking(retryPolicy, s"OCC-leaky($key)")(
      singleAttempt(key, cost, profile),
    ).map(_.result).handleErrorWith { case _: OCCConflictException =>
      Clock[F].realTime.map(_.toMillis).map { now =>
        val secToAllow = cost.toDouble / profile.refillRatePerSecond
        val resetAt = Instant.ofEpochMilli(now + (secToAllow * 1000).toLong)
        RateLimitDecision.Rejected(secToAllow.ceil.toInt.max(1), resetAt)
      }
    }

  /** Single attempt: one read-compute-write cycle. On OCC conflict
    * (conditionalPut returns false), raise OCCConflictException so Retry
    * handles backoff.
    */
  private def singleAttempt(
      key: String,
      cost: Int,
      profile: RateLimitProfile,
  ): F[RateLimitDecision] =
    for
      now <- Clock[F].realTime.map(_.toMillis)
      currentState <- getOrInitState(key, profile, now)
      elapsedSec = (now - currentState.lastLeakMs) / 1000.0
      leakAmount = elapsedSec * profile.refillRatePerSecond
      newLevel = math.max(0.0, currentState.level - leakAmount)
      decision <-
        if newLevel + cost <= profile.capacity then
          val updatedLevel = newLevel + cost
          val newState =
            LeakyBucketState(updatedLevel, now, currentState.version + 1)
          attemptUpdate(
            key,
            currentState.version,
            newState,
            profile.ttlSeconds,
            now,
          ).flatMap {
            case true =>
              val resetAt = Instant.ofEpochMilli(
                now + (updatedLevel / profile.refillRatePerSecond * 1000).toLong,
              )
              Async[F].pure(
                RateLimitDecision
                  .Allowed((profile.capacity - updatedLevel).toInt, resetAt),
              )
            case false => Async[F].raiseError(OCCConflictException(key, 0))
          }
        else
          val secToAllow =
            (newLevel + cost - profile.capacity) / profile.refillRatePerSecond
          val resetAt = Instant.ofEpochMilli(now + (secToAllow * 1000).toLong)
          Async[F].pure(
            RateLimitDecision.Rejected(secToAllow.ceil.toInt.max(1), resetAt),
          )
    yield decision

  override def getStatus(
      key: String,
      profile: RateLimitProfile,
  ): F[Option[RateLimitDecision.Allowed]] =
    for
      now <- Clock[F].realTime.map(_.toMillis)
      maybeState <- getState(key)
      result <- maybeState match
        case Some(Right(state)) =>
          val elapsedSec = (now - state.lastLeakMs) / 1000.0
          val leakAmount = elapsedSec * profile.refillRatePerSecond
          val newLevel = math.max(0.0, state.level - leakAmount)
          val remaining = (profile.capacity - newLevel).toInt
          val resetAt = Instant.ofEpochMilli(
            now + (newLevel / profile.refillRatePerSecond * 1000).toLong,
          )
          Async[F].pure(Some(RateLimitDecision.Allowed(remaining, resetAt)))
        case Some(Left(err)) => logger.error(
            s"Corrupt rate-limit state for key=$key: $err — returning no status",
          ) *> metrics.increment("CorruptStateRead").as(None)
        case None => Async[F].pure(None)
    yield result

  override def healthCheck: F[Either[String, Unit]] =
    dynamoHealthCheck(client, tableName)

  private def getOrInitState(
      key: String,
      profile: RateLimitProfile,
      now: Long,
  ): F[LeakyBucketState] = getState(key).flatMap {
    case Some(Right(state)) => Async[F].pure(state)
    case Some(Left(err)) => logger
        .error(s"Corrupt rate-limit state for key $key: $err — failing open") *>
        metrics.increment("CorruptStateRead") *>
        Async[F].pure(LeakyBucketState(0.0, now, 0L))
    case None => Async[F].pure(LeakyBucketState(0.0, now, 0L))
  }

  private def getState(
      key: String,
  ): F[Option[Either[String, LeakyBucketState]]] =
    val request = GetItemRequest.builder().tableName(tableName)
      .key(Map("pk" -> attr(s"ratelimit#$key")).asJava).consistentRead(true)
      .build()
    Async[F].fromCompletableFuture(
      Async[F].delay(client.getItem(request).toCompletableFuture),
    ).map(response =>
      if response.hasItem && !response.item().isEmpty then
        Some(parseState(response.item().asScala.toMap))
      else None,
    )

  private def parseState(
      item: Map[String, AttributeValue],
  ): Either[String, LeakyBucketState] =
    try
      val level = item.get("tokens").toRight("missing 'tokens' attribute")
        .map(_.n().toDouble)
      val lastLeakMs = item.get("lastRefillMs")
        .toRight("missing 'lastRefillMs' attribute").map(_.n().toLong)
      val version = item.get("version").toRight("missing 'version' attribute")
        .map(_.n().toLong)
      for
        l <- level
        lm <- lastLeakMs
        v <- version
      yield LeakyBucketState(l, lm, v)
    catch
      case e: NumberFormatException =>
        Left(s"malformed numeric attribute: ${e.getMessage}")

  private def attemptUpdate(
      key: String,
      expectedVersion: Long,
      newState: LeakyBucketState,
      ttlSeconds: Long,
      now: Long,
  ): F[Boolean] =
    val ttl = now / 1000 + ttlSeconds

    val item = Map(
      "pk" -> attr(s"ratelimit#$key"),
      "tokens" -> attrND(newState.level),
      "lastRefillMs" -> attrN(newState.lastLeakMs),
      "version" -> attrN(newState.version),
      "ttl" -> attrN(ttl),
    )

    val requestBuilder = PutItemRequest.builder().tableName(tableName)
      .item(item.asJava)
    val request =
      if expectedVersion == 0L then
        requestBuilder.conditionExpression("attribute_not_exists(pk)").build()
      else
        requestBuilder.conditionExpression("version = :expectedVersion")
          .expressionAttributeValues(
            Map(":expectedVersion" -> attrN(expectedVersion)).asJava,
          ).build()

    conditionalPut(client, request)

  private case class LeakyBucketState(
      level: Double,
      lastLeakMs: Long,
      version: Long,
  )

object LeakyBucketRateLimitStore:
  def apply[F[_]: Async: Logger](
      client: DynamoDbAsyncClient,
      tableName: String,
      metrics: MetricsPublisher[F],
      retryPolicy: RetryPolicy = RetryPolicy.occRetry,
  ): LeakyBucketRateLimitStore[F] =
    new LeakyBucketRateLimitStore[F](client, tableName, metrics, retryPolicy)
