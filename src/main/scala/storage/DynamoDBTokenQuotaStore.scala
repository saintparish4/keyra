package storage

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

import cats.effect.*
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import core.{TokenQuotaState, TokenQuotaStore}
import _root_.metrics.MetricsPublisher
import DynamoDBOps.*

/** DynamoDB-backed token quota store.
  *
  * Table schema (keyra-token-quotas):
  *   - pk (S): "{level}:{id}:{window}" e.g. "user:u123:3600s"
  *   - input_tokens (N): cumulative input tokens in current window
  *   - output_tokens (N): cumulative output tokens in current window
  *   - window_start (N): epoch millis when the current window began
  *   - version (N): OCC version counter
  *   - ttl (N): epoch seconds for DynamoDB TTL cleanup
  *
  * Uses OCC via retryOnConditionFail from DynamoDBOps.
  */
class DynamoDBTokenQuotaStore[F[_]: Async](
    client: DynamoDbAsyncClient,
    tableName: String,
    logger: Logger[F],
    metrics: MetricsPublisher[F],
) extends TokenQuotaStore[F]:

  private val MaxRetries = 25

  override def getQuota(pk: String): F[Option[TokenQuotaState]] =
    val request = GetItemRequest.builder().tableName(tableName)
      .key(Map("pk" -> attr(pk)).asJava)
      .consistentRead(true)
      .build()

    Async[F].fromCompletableFuture(
      Async[F].delay(client.getItem(request).toCompletableFuture),
    ).flatMap { response =>
      if response.hasItem && !response.item().isEmpty then
        parseState(response.item().asScala.toMap) match
          case Right(state) => Async[F].pure(Some(state))
          case Left(err) =>
            logger.error(s"Corrupt token quota state for pk=$pk: $err") *>
              metrics.increment("CorruptStateRead") *>
              Async[F].pure(None)
      else Async[F].pure(None)
    }

  override def incrementQuota(
      pk: String,
      inputTokensDelta: Long,
      outputTokensDelta: Long,
      windowSeconds: Long,
      nowMs: Long,
  ): F[Boolean] =
    incrementWithRetry(pk, inputTokensDelta, outputTokensDelta, windowSeconds, nowMs, MaxRetries)

  override def healthCheck: F[Either[String, Unit]] =
    dynamoHealthCheck(client, tableName)

  private def incrementWithRetry(
      pk: String,
      inputDelta: Long,
      outputDelta: Long,
      windowSec: Long,
      nowMs: Long,
      retriesRemaining: Int,
  ): F[Boolean] =
    for
      current <- getQuota(pk)
      result <- current match
        case Some(state) if isWithinWindow(state.windowStart, nowMs, windowSec) =>
          val newInput = math.max(0, state.inputTokens + inputDelta)
          val newOutput = math.max(0, state.outputTokens + outputDelta)
          val newVersion = state.version + 1
          val ttl = nowMs / 1000 + windowSec + 60 // window + 60s grace

          attemptConditionalUpdate(
            pk, newInput, newOutput, state.windowStart, newVersion, state.version, ttl,
          )          .flatMap {
            case true => Async[F].pure(true)
            case false =>
              if retriesRemaining > 0 then
                metrics.increment("TokenQuotaOCCRetry") *>
                  jitteredBackoff(MaxRetries - retriesRemaining) *>
                  incrementWithRetry(pk, inputDelta, outputDelta, windowSec, nowMs, retriesRemaining - 1)
              else
                logger.warn(s"OCC retries exhausted for token quota pk=$pk") *>
                  Async[F].pure(false)
          }

        case _ =>
          val inputTokens = math.max(0, inputDelta)
          val outputTokens = math.max(0, outputDelta)
          val ttl = nowMs / 1000 + windowSec + 60

          attemptCreateNew(pk, inputTokens, outputTokens, nowMs, ttl).flatMap {
            case true => Async[F].pure(true)
            case false =>
              if retriesRemaining > 0 then
                metrics.increment("TokenQuotaOCCRetry") *>
                  jitteredBackoff(MaxRetries - retriesRemaining) *>
                  incrementWithRetry(pk, inputDelta, outputDelta, windowSec, nowMs, retriesRemaining - 1)
              else
                logger.warn(s"OCC retries exhausted for new token quota pk=$pk") *>
                  Async[F].pure(false)
          }
    yield result

  private def jitteredBackoff(attempt: Int): F[Unit] =
    val baseMs = math.min(1L << attempt, 64L) // 1, 2, 4, 8, 16, 32, 64 cap
    Async[F].delay(scala.util.Random.nextLong(baseMs + 1))
      .flatMap(jitter => Async[F].sleep(jitter.millis))

  private def attemptConditionalUpdate(
      pk: String,
      inputTokens: Long,
      outputTokens: Long,
      windowStart: Long,
      newVersion: Long,
      expectedVersion: Long,
      ttl: Long,
  ): F[Boolean] =
    val item = Map(
      "pk" -> attr(pk),
      "input_tokens" -> attrN(inputTokens),
      "output_tokens" -> attrN(outputTokens),
      "window_start" -> attrN(windowStart),
      "version" -> attrN(newVersion),
      "ttl" -> attrN(ttl),
    )
    val request = PutItemRequest.builder().tableName(tableName)
      .item(item.asJava)
      .conditionExpression("version = :expectedVersion")
      .expressionAttributeValues(
        Map(":expectedVersion" -> attrN(expectedVersion)).asJava,
      ).build()

    conditionalPut(client, request)

  private def attemptCreateNew(
      pk: String,
      inputTokens: Long,
      outputTokens: Long,
      windowStart: Long,
      ttl: Long,
  ): F[Boolean] =
    val item = Map(
      "pk" -> attr(pk),
      "input_tokens" -> attrN(inputTokens),
      "output_tokens" -> attrN(outputTokens),
      "window_start" -> attrN(windowStart),
      "version" -> attrN(1L),
      "ttl" -> attrN(ttl),
    )
    val request = PutItemRequest.builder().tableName(tableName)
      .item(item.asJava)
      .conditionExpression("attribute_not_exists(pk)")
      .build()

    conditionalPut(client, request)

  private def parseState(
      item: Map[String, AttributeValue],
  ): Either[String, TokenQuotaState] =
    try
      val inputTokens = item.get("input_tokens")
        .toRight("missing 'input_tokens'").map(_.n().toLong)
      val outputTokens = item.get("output_tokens")
        .toRight("missing 'output_tokens'").map(_.n().toLong)
      val windowStart = item.get("window_start")
        .toRight("missing 'window_start'").map(_.n().toLong)
      val version = item.get("version")
        .toRight("missing 'version'").map(_.n().toLong)
      for
        i <- inputTokens
        o <- outputTokens
        w <- windowStart
        v <- version
      yield TokenQuotaState(i, o, w, v)
    catch
      case e: NumberFormatException =>
        Left(s"malformed numeric attribute: ${e.getMessage}")

  private def isWithinWindow(windowStart: Long, nowMs: Long, windowSec: Long): Boolean =
    (nowMs - windowStart) < (windowSec * 1000)

object DynamoDBTokenQuotaStore:
  def apply[F[_]: Async](
      client: DynamoDbAsyncClient,
      tableName: String,
      logger: Logger[F],
      metrics: MetricsPublisher[F],
  ): DynamoDBTokenQuotaStore[F] =
    new DynamoDBTokenQuotaStore[F](client, tableName, logger, metrics)