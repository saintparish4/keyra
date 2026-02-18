package storage

import java.time.Instant

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

import cats.effect.*
import cats.syntax.all.*
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import core.{RateLimitDecision, RateLimitProfile, RateLimitStore}
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
class LeakyBucketRateLimitStore[F[_]: Async](
    client: DynamoDbAsyncClient,
    tableName: String,
) extends RateLimitStore[F]:

  private val MaxRetries = 10

  override def checkAndConsume(
      key: String,
      cost: Int,
      profile: RateLimitProfile,
  ): F[RateLimitDecision] =
    checkAndConsumeWithRetry(key, cost, profile, MaxRetries)

  private def checkAndConsumeWithRetry(
      key: String,
      cost: Int,
      profile: RateLimitProfile,
      retriesRemaining: Int,
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
          attemptUpdate(key, currentState.version, newState, profile.ttlSeconds)
            .flatMap {
              case true =>
                val resetAt = Instant.ofEpochMilli(
                  now + (updatedLevel / profile.refillRatePerSecond * 1000)
                    .toLong,
                )
                Async[F].pure(
                  RateLimitDecision
                    .Allowed((profile.capacity - updatedLevel).toInt, resetAt),
                )
              case false =>
                if retriesRemaining > 0 then
                  Async[F].sleep(1.millis) *> checkAndConsumeWithRetry(
                    key,
                    cost,
                    profile,
                    retriesRemaining - 1,
                  )
                else
                  val secToAllow = (newLevel + cost - profile.capacity) /
                    profile.refillRatePerSecond
                  val resetAt = Instant
                    .ofEpochMilli(now + (secToAllow * 1000).toLong)
                  Async[F].pure(
                    RateLimitDecision
                      .Rejected(secToAllow.ceil.toInt.max(1), resetAt),
                  )
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
      result = maybeState.map { state =>
        val elapsedSec = (now - state.lastLeakMs) / 1000.0
        val leakAmount = elapsedSec * profile.refillRatePerSecond
        val newLevel = math.max(0.0, state.level - leakAmount)
        val remaining = (profile.capacity - newLevel).toInt
        val resetAt = Instant.ofEpochMilli(
          now + (newLevel / profile.refillRatePerSecond * 1000).toLong,
        )
        RateLimitDecision.Allowed(remaining, resetAt)
      }
    yield result

  override def healthCheck: F[Either[String, Unit]] = Async[F]
    .fromCompletableFuture(Async[F].delay(
      client
        .describeTable(DescribeTableRequest.builder().tableName(tableName).build())
        .toCompletableFuture,
    )).map(_ => Right(())).handleError(e => Left(e.getMessage))

  private def getOrInitState(
      key: String,
      profile: RateLimitProfile,
      now: Long,
  ): F[LeakyBucketState] = getState(key)
    .map(_.getOrElse(LeakyBucketState(0.0, now, 0L)))

  private def getState(key: String): F[Option[LeakyBucketState]] =
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

  private def parseState(item: Map[String, AttributeValue]): LeakyBucketState =
    LeakyBucketState(
      level = item.get("tokens").map(_.n().toDouble).getOrElse(0.0),
      lastLeakMs = item.get("lastRefillMs").map(_.n().toLong).getOrElse(0L),
      version = item.get("version").map(_.n().toLong).getOrElse(0L),
    )

  private def attemptUpdate(
      key: String,
      expectedVersion: Long,
      newState: LeakyBucketState,
      ttlSeconds: Long,
  ): F[Boolean] =
    val ttl = System.currentTimeMillis() / 1000 + ttlSeconds

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
  def apply[F[_]: Async](
      client: DynamoDbAsyncClient,
      tableName: String,
  ): LeakyBucketRateLimitStore[F] =
    new LeakyBucketRateLimitStore[F](client, tableName)
