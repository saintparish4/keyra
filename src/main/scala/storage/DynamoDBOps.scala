package storage

import scala.jdk.FutureConverters.*

import cats.effect.Async
import cats.syntax.all.*
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*

/** Shared DynamoDB helper utilities used by all store implementations.
  *
  * Centralises AttributeValue construction and conditional-write wrappers so
  * each store avoids repeating the same boilerplate. Both wrappers return
  * `false` on `ConditionalCheckFailedException` rather than propagating the
  * exception, keeping OCC retry logic uniform across callers.
  */
object DynamoDBOps:

  def attr(s: String): AttributeValue =
    AttributeValue.builder().s(s).build()

  def attrN(n: Long): AttributeValue =
    AttributeValue.builder().n(n.toString).build()

  def attrND(d: Double): AttributeValue =
    AttributeValue.builder().n(d.toString).build()

  def attrBool(b: Boolean): AttributeValue =
    AttributeValue.builder().bool(b).build()

  /** Execute a conditional PutItem; returns false on condition failure. */
  def conditionalPut[F[_]: Async](
      client: DynamoDbAsyncClient,
      request: PutItemRequest,
  ): F[Boolean] =
    Async[F]
      .fromCompletableFuture(Async[F].delay(client.putItem(request).toCompletableFuture))
      .map(_ => true)
      .recover { case _: ConditionalCheckFailedException => false }

  /** Execute a conditional UpdateItem; returns false on condition failure. */
  def conditionalUpdate[F[_]: Async](
      client: DynamoDbAsyncClient,
      request: UpdateItemRequest,
  ): F[Boolean] =
    Async[F]
      .fromCompletableFuture(Async[F].delay(client.updateItem(request).toCompletableFuture))
      .map(_ => true)
      .recover { case _: ConditionalCheckFailedException => false }
