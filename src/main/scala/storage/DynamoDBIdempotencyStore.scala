package storage

import java.time.Instant

import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

import cats.effect.*
import cats.syntax.all.*
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import io.circe.*
import io.circe.generic.auto.*
import io.circe.parser.*
import io.circe.syntax.*
import core.{
  IdempotencyRecord, IdempotencyResult, IdempotencyStatus, IdempotencyStore,
  StoredResponse,
}
import DynamoDBOps.*

/** DynamoDB implementation of IdempotencyStore.
  *
  * Uses first-writer-wins semantics with conditional writes to ensure only one
  * instance processes a request with a given idempotency key.
  *
  * Table Schema:
  *   - pk (S): Partition key - "idempotency#<key>"
  *   - clientId (S): Client making the request
  *   - status (S): Pending | Completed | Failed
  *   - response (S): JSON-encoded response (if completed)
  *   - createdAt (N): Creation timestamp
  *   - updatedAt (N): Last update timestamp
  *   - version (N): Version for OCC
  *   - ttl (N): TTL for automatic cleanup
  */
class DynamoDBIdempotencyStore[F[_]: Async](
    client: DynamoDbAsyncClient,
    tableName: String,
) extends IdempotencyStore[F]:

  override def check(
      idempotencyKey: String,
      clientId: String,
      ttlSeconds: Long,
  ): F[IdempotencyResult] =
    for
      now <- Clock[F].realTime.map(d => Instant.ofEpochMilli(d.toMillis))

      // Try to create new record with condition
      result <- tryCreatePending(idempotencyKey, clientId, now, ttlSeconds)
        .flatMap {
          case true => Async[F].pure(IdempotencyResult.New(idempotencyKey, now))
          case false =>
            // Record exists - check its status
            get(idempotencyKey).map {
              case Some(record) => record.status match
                  case IdempotencyStatus.Pending => IdempotencyResult
                      .InProgress(idempotencyKey, record.createdAt)
                  case IdempotencyStatus.Completed => IdempotencyResult
                      .Duplicate(
                        idempotencyKey,
                        record.response,
                        record.createdAt,
                      )
                  case IdempotencyStatus.Failed =>
                    // Allow retry - try to update to Pending
                    IdempotencyResult.New(idempotencyKey, now) // Simplified: just allow retry
              case None =>
                // Rare race condition - record was deleted
                IdempotencyResult.New(idempotencyKey, now)
            }
        }
    yield result

  override def storeResponse(
      idempotencyKey: String,
      response: StoredResponse,
  ): F[Boolean] =
    for
      now <- Clock[F].realTime.map(d => Instant.ofEpochMilli(d.toMillis))

      request = UpdateItemRequest.builder().tableName(tableName)
        .key(Map("pk" -> attr(s"idempotency#$idempotencyKey")).asJava)
        .updateExpression(
          "SET #status = :status, #response = :response, #updatedAt = :updatedAt, #version = #version + :one",
        ).conditionExpression("#status = :pending").expressionAttributeNames(
          Map(
            "#status"    -> "status",
            "#response"  -> "response",
            "#updatedAt" -> "updatedAt",
            "#version"   -> "version",
          ).asJava,
        ).expressionAttributeValues(
          Map(
            ":status"    -> attr("Completed"),
            ":response"  -> attr(response.asJson.noSpaces),
            ":updatedAt" -> attrN(now.toEpochMilli),
            ":pending"   -> attr("Pending"),
            ":one"       -> attrN(1),
          ).asJava,
        ).build()

      result <- conditionalUpdate(client, request)
    yield result

  override def markFailed(idempotencyKey: String): F[Boolean] =
    for
      now <- Clock[F].realTime.map(d => Instant.ofEpochMilli(d.toMillis))

      request = UpdateItemRequest.builder().tableName(tableName)
        .key(Map("pk" -> attr(s"idempotency#$idempotencyKey")).asJava)
        .updateExpression("SET #status = :status, #updatedAt = :updatedAt")
        .conditionExpression("attribute_exists(pk)").expressionAttributeNames(
          Map("#status" -> "status", "#updatedAt" -> "updatedAt").asJava,
        ).expressionAttributeValues(
          Map(
            ":status"    -> attr("Failed"),
            ":updatedAt" -> attrN(now.toEpochMilli),
          ).asJava,
        ).build()

      result <- conditionalUpdate(client, request)
    yield result

  override def get(idempotencyKey: String): F[Option[IdempotencyRecord]] =
    val request = GetItemRequest.builder().tableName(tableName)
      .key(Map("pk" -> attr(s"idempotency#$idempotencyKey")).asJava)
      .consistentRead(true)
      .build()

    Async[F].fromCompletableFuture(
      Async[F].delay(client.getItem(request).toCompletableFuture),
    ).map(response =>
      if response.hasItem && !response.item().isEmpty then
        Some(parseRecord(idempotencyKey, response.item().asScala.toMap))
      else None,
    )

  override def healthCheck: F[Boolean] = Async[F]
    .fromCompletableFuture(Async[F].delay(
      client
        .describeTable(DescribeTableRequest.builder().tableName(tableName).build())
        .toCompletableFuture,
    )).map(_ => true).handleError(_ => false)

  private def tryCreatePending(
      idempotencyKey: String,
      clientId: String,
      now: Instant,
      ttlSeconds: Long,
  ): F[Boolean] =
    val ttl = now.getEpochSecond + ttlSeconds

    val item = Map(
      "pk"        -> attr(s"idempotency#$idempotencyKey"),
      "clientId"  -> attr(clientId),
      "status"    -> attr("Pending"),
      "createdAt" -> attrN(now.toEpochMilli),
      "updatedAt" -> attrN(now.toEpochMilli),
      "version"   -> attrN(1),
      "ttl"       -> attrN(ttl),
    )

    val request = PutItemRequest.builder().tableName(tableName).item(item.asJava)
      .conditionExpression("attribute_not_exists(pk) OR #status = :failed")
      .expressionAttributeNames(Map("#status" -> "status").asJava)
      .expressionAttributeValues(Map(":failed" -> attr("Failed")).asJava)
      .build()

    conditionalPut(client, request)

  private def parseRecord(
      idempotencyKey: String,
      item: Map[String, AttributeValue],
  ): IdempotencyRecord =
    val status = item.get("status").map(_.s()) match
      case Some("Pending") => IdempotencyStatus.Pending
      case Some("Completed") => IdempotencyStatus.Completed
      case Some("Failed") => IdempotencyStatus.Failed
      case _ => IdempotencyStatus.Pending

    val response = item.get("response")
      .flatMap(av => decode[StoredResponse](av.s()).toOption)

    val createdAt = item.get("createdAt")
      .map(a => Instant.ofEpochMilli(a.n().toLong)).getOrElse(Instant.now())

    val updatedAt = item.get("updatedAt")
      .map(a => Instant.ofEpochMilli(a.n().toLong)).getOrElse(createdAt)

    IdempotencyRecord(
      idempotencyKey = idempotencyKey,
      clientId = item.get("clientId").map(_.s()).getOrElse("unknown"),
      status = status,
      response = response,
      createdAt = createdAt,
      updatedAt = updatedAt,
      ttl = item.get("ttl").map(_.n().toLong).getOrElse(0L),
      version = item.get("version").map(_.n().toLong).getOrElse(0L),
    )

object DynamoDBIdempotencyStore:
  def apply[F[_]: Async](
      client: DynamoDbAsyncClient,
      tableName: String,
  ): DynamoDBIdempotencyStore[F] =
    new DynamoDBIdempotencyStore[F](client, tableName)
