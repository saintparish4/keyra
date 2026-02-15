package api

import java.time.Instant

import org.http4s.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.Logger

import cats.effect.*
import cats.effect.syntax.spawn.*
import cats.syntax.all.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import config.IdempotencyConfig
import core.*
import events.*
import _root_.metrics.MetricsPublisher
import security.*

/** Idempotency API endpoints.
  *
  * Provides first-writer-wins idempotency for distributed operations.
  */
class IdempotencyApi[F[_]: Async](
    store: IdempotencyStore[F],
    idempotencyConfig: IdempotencyConfig,
    eventPublisher: EventPublisher[F],
    metricsPublisher: MetricsPublisher[F],
    logger: Logger[F],
) extends Http4sDsl[F]:

  /** POST /v1/idempotency/check
    *
    * Check if an operation with this idempotency key has been processed before.
    */
  def check(request: Request[F], client: AuthenticatedClient): F[Response[F]] =
    for
      startTime <- Clock[F].realTime.map(_.toMillis)
      checkReq <- request.as[IdempotencyCheckRequest]

      requestedTtl = checkReq.ttl.getOrElse(idempotencyConfig.defaultTtlSeconds)
      ttlSeconds = math.min(requestedTtl, idempotencyConfig.maxTtlSeconds)
      _ <- if requestedTtl > idempotencyConfig.maxTtlSeconds then
        logger.warn(
          s"Idempotency TTL capped: requested=$requestedTtl, max=${idempotencyConfig.maxTtlSeconds}, key=${checkReq.idempotencyKey}"
        )
      else ().pure[F]

      // Perform idempotency check
      _ <- logger.debug(s"Idempotency check: key=${checkReq
          .idempotencyKey}, client=${client.apiKeyId}")
      result <- store.check(
        checkReq.idempotencyKey,
        client.apiKeyId,
        ttlSeconds,
      )

      // Record metrics
      latency <- Clock[F].realTime.map(_.toMillis - startTime)
      _ <- metricsPublisher.recordLatency("idempotency_check", latency.toDouble)

      // Publish event (fire and forget)
      now <- Clock[F].realTime.map(d => Instant.ofEpochMilli(d.toMillis))
      _ <- publishEvent(result, client, ttlSeconds, now).start

      // Build response
      response <- buildCheckResponse(result)
    yield response

  /** POST /v1/idempotency/:key/complete
    *
    * Store the response for a completed idempotent operation.
    */
  def complete(
      key: String,
      request: Request[F],
      client: AuthenticatedClient,
  ): F[Response[F]] =
    for
      completeReq <- request.as[IdempotencyCompleteRequest]
      now <- Clock[F].realTime.map(d => Instant.ofEpochMilli(d.toMillis))

      storedResponse = StoredResponse(
        statusCode = completeReq.statusCode,
        body = completeReq.body,
        headers = completeReq.headers.getOrElse(Map.empty),
        completedAt = now,
      )

      _ <- logger
        .debug(s"Completing idempotency key: $key, client=${client.apiKeyId}")
      success <- store.storeResponse(key, storedResponse)

      response <-
        if success then
          Ok(
            IdempotencyCompleteResponse(
              idempotencyKey = key,
              status = "completed",
            ).asJson,
          )
        else
          Conflict(
            IdempotencyCompleteResponse(
              idempotencyKey = key,
              status = "failed",
              message = Some(
                "Could not store response - key may not exist or is not pending",
              ),
            ).asJson,
          )
    yield response

  private def buildCheckResponse(result: IdempotencyResult): F[Response[F]] =
    result match
      case IdempotencyResult.New(key, _) => Ok(
          IdempotencyCheckResponse(
            status = "new",
            idempotencyKey = key,
            originalResponse = None,
          ).asJson,
        )

      case IdempotencyResult.Duplicate(key, response, firstSeenAt) => Ok(
          IdempotencyCheckResponse(
            status = "duplicate",
            idempotencyKey = key,
            originalResponse = response.map(r =>
              OriginalResponse(
                statusCode = r.statusCode,
                body = r.body,
                headers = r.headers,
              ),
            ),
            firstSeenAt = Some(firstSeenAt.toString),
          ).asJson,
        )

      case IdempotencyResult.InProgress(key, startedAt) => Accepted(
          IdempotencyCheckResponse(
            status = "in_progress",
            idempotencyKey = key,
            originalResponse = None,
            firstSeenAt = Some(startedAt.toString),
            message = Some("Operation is currently being processed"),
          ).asJson,
        )

  private def publishEvent(
      result: IdempotencyResult,
      client: AuthenticatedClient,
      ttlSeconds: Long,
      timestamp: Instant,
  ): F[Unit] =
    val event = result match
      case IdempotencyResult.New(key, _) => RateLimitEvent.IdempotencyNew(
          timestamp = timestamp,
          idempotencyKey = key,
          clientId = client.apiKeyId,
          ttlSeconds = ttlSeconds,
        )
      case IdempotencyResult.Duplicate(key, _, firstSeenAt) => RateLimitEvent
          .IdempotencyHit(
            timestamp = timestamp,
            idempotencyKey = key,
            clientId = client.apiKeyId,
            originalRequestTime = firstSeenAt,
          )
      case IdempotencyResult.InProgress(key, startedAt) => RateLimitEvent
          .IdempotencyHit(
            timestamp = timestamp,
            idempotencyKey = key,
            clientId = client.apiKeyId,
            originalRequestTime = startedAt,
          )

    eventPublisher.publish(event).handleError(error =>
      logger.warn(s"Failed to publish idempotency event: ${error.getMessage}"),
    )

// Request/Response models
case class IdempotencyCheckRequest(
    idempotencyKey: String,
    ttl: Option[Long] = None,
)

case class IdempotencyCheckResponse(
    status: String,
    idempotencyKey: String,
    originalResponse: Option[OriginalResponse] = None,
    firstSeenAt: Option[String] = None,
    message: Option[String] = None,
)

case class OriginalResponse(
    statusCode: Int,
    body: String,
    headers: Map[String, String],
)

case class IdempotencyCompleteRequest(
    statusCode: Int,
    body: String,
    headers: Option[Map[String, String]] = None,
)

case class IdempotencyCompleteResponse(
    idempotencyKey: String,
    status: String,
    message: Option[String] = None,
)

object IdempotencyApi:
  def apply[F[_]: Async](
      store: IdempotencyStore[F],
      idempotencyConfig: IdempotencyConfig,
      eventPublisher: EventPublisher[F],
      metricsPublisher: MetricsPublisher[F],
      logger: Logger[F],
  ): IdempotencyApi[F] =
    new IdempotencyApi[F](
      store,
      idempotencyConfig,
      eventPublisher,
      metricsPublisher,
      logger,
    )
