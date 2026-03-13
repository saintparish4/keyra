package storage

import scala.concurrent.duration.*
import scala.jdk.FutureConverters.*

import org.typelevel.log4cats.Logger

import cats.effect.{Async, Temporal}
import cats.syntax.all.*
import resilience.{OCCConflictException, Retry, RetryPolicy}
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

  def attr(s: String): AttributeValue = AttributeValue.builder().s(s).build()

  def attrN(n: Long): AttributeValue = AttributeValue.builder().n(n.toString)
    .build()

  def attrND(d: Double): AttributeValue = AttributeValue.builder().n(d.toString)
    .build()

  def attrBool(b: Boolean): AttributeValue = AttributeValue.builder().bool(b)
    .build()

  /** Execute a conditional PutItem; returns false on condition failure. */
  def conditionalPut[F[_]: Async](
      client: DynamoDbAsyncClient,
      request: PutItemRequest,
  ): F[Boolean] = Async[F].fromCompletableFuture(
    Async[F].delay(client.putItem(request).toCompletableFuture),
  ).map(_ => true).recover { case _: ConditionalCheckFailedException => false }

  /** Execute a conditional UpdateItem; returns false on condition failure. */
  def conditionalUpdate[F[_]: Async](
      client: DynamoDbAsyncClient,
      request: UpdateItemRequest,
  ): F[Boolean] = Async[F].fromCompletableFuture(
    Async[F].delay(client.updateItem(request).toCompletableFuture),
  ).map(_ => true).recover { case _: ConditionalCheckFailedException => false }

  /** OCC retry loop: run `attempt`, on false raise OCCConflictException so
    * Retry engine handles backoff; on exhaustion after retries return
    * `onExhaustion`. Delegates to Retry.withPolicy internally.
    */
  def retryOnConditionFail[F[_]: Temporal: Logger, A](
      attempt: F[Boolean],
      maxRetries: Int,
      delay: FiniteDuration = 1.millis,
  )(onSuccess: F[A])(onExhaustion: F[A])(
      onRetry: Option[F[Unit]] = None,
  ): F[A] =
    val policy = RetryPolicy(
      maxRetries = maxRetries,
      baseDelay = delay,
      maxDelay = delay * 10,
      multiplier = 1.0,
      jitterFactor = 0.0,
      retryOn = { case _: OCCConflictException => true; case _ => false },
    )
    val retryAction = onRetry.getOrElse(Temporal[F].unit)
    val op: F[A] = attempt.flatMap {
      case true => onSuccess
      case false => retryAction *>
          Temporal[F].raiseError(OCCConflictException("condition-fail", 0))
    }
    Retry.withPolicy(policy, "conditionalWrite")(op).handleErrorWith {
      case _: OCCConflictException => onExhaustion
    }

  /** Standard DynamoDB table health check via describeTable. */
  def dynamoHealthCheck[F[_]: Async](
      client: DynamoDbAsyncClient,
      tableName: String,
  ): F[Either[String, Unit]] = Async[F].fromCompletableFuture(Async[F].delay(
    client
      .describeTable(DescribeTableRequest.builder().tableName(tableName).build())
      .toCompletableFuture,
  )).map(_ => Right(())).handleError(e => Left(e.getMessage))
