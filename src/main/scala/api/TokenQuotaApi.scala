package api

import java.time.Instant
import java.util.UUID  

import org.http4s.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.Http4sDsl
import org.typelevel.ci.*
import org.typelevel.log4cats.Logger

import cats.effect.*
import cats.effect.syntax.spawn.*
import cats.syntax.all.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import core.*
import events.*
import _root_.metrics.MetricsPublisher
import _root_.metrics.TracingMiddleware
import org.typelevel.otel4s.trace.Tracer
import security.*

class TokenQuotaApi[F[_]: Async: Tracer](
    quotaService: TokenQuotaService[F],
    eventPublisher: EventPublisher[F],
    metricsPublisher: MetricsPublisher[F],
    logger: Logger[F],
) extends Http4sDsl[F]:

  /** POST /v1/quota/check
    *
    * Pre-request: check if estimated token usage is within quota.
    */
  def check(request: Request[F], client: AuthenticatedClient): F[Response[F]] =
    for
      startTime <- Clock[F].realTime.map(_.toMillis)
      req <- request.as[TokenQuotaCheckRequest]

      response <-
        if req.estimatedInputTokens < 0 || req.estimatedOutputTokens < 0 then
          BadRequest(io.circe.Json.obj(
            "error" -> io.circe.Json.fromString("validation_error"),
            "message" -> io.circe.Json.fromString("token estimates must be non-negative"),
          ))
        else
          for
            identifier <- Async[F].pure(QuotaIdentifier(
              userId = req.userId,
              agentId = req.agentId,
              orgId = req.orgId,
            ))
            decision <- TracingMiddleware.traced("checkQuota") {
              quotaService.checkQuota(
                identifier, req.estimatedInputTokens, req.estimatedOutputTokens,
              )
            }

            latency <- Clock[F].realTime.map(_.toMillis - startTime)
            _ <- metricsPublisher.recordLatency("token_quota_check", latency.toDouble)

            now <- Clock[F].realTime.map(d => Instant.ofEpochMilli(d.toMillis))
            traceId <- currentTraceId
            _ <- publishQuotaEvent(decision, req, client, now, traceId).start

            resp <- buildCheckResponse(decision)
          yield resp
    yield response

  /** POST /v1/quota/reconcile
    *
    * Post-LLM-call: adjust quota counters with actual token usage.
    */
  def reconcile(request: Request[F], client: AuthenticatedClient): F[Response[F]] =
    for
      startTime <- Clock[F].realTime.map(_.toMillis)
      req <- request.as[TokenQuotaReconcileRequest]

      identifier = QuotaIdentifier(
        userId = req.userId,
        agentId = req.agentId,
        orgId = req.orgId,
      )

      _ <- quotaService.reconcile(
        identifier,
        req.actualInputTokens,
        req.actualOutputTokens,
        req.estimatedInputTokens,
        req.estimatedOutputTokens,
      )

      latency <- Clock[F].realTime.map(_.toMillis - startTime)
      _ <- metricsPublisher.recordLatency("token_quota_reconcile", latency.toDouble)

      resp <- Ok(TokenQuotaReconcileResponse(
        status = "reconciled",
        inputDelta = req.actualInputTokens - req.estimatedInputTokens,
        outputDelta = req.actualOutputTokens - req.estimatedOutputTokens,
      ).asJson)
    yield resp

  private def buildCheckResponse(decision: QuotaDecision): F[Response[F]] =
    decision match
      case QuotaDecision.Available(remaining) =>
        Ok(TokenQuotaCheckResponse(
          allowed = true,
          remainingTokens = remaining.map { case (level, rem) => level.prefix -> rem },
          exceededLevel = None,
          retryAfter = None,
        ).asJson)
      case QuotaDecision.Exceeded(level, limit, used, retryAfter) =>
        TooManyRequests(TokenQuotaCheckResponse(
          allowed = false,
          remainingTokens = Map.empty,
          exceededLevel = Some(level.prefix),
          retryAfter = Some(retryAfter),
          message = Some(s"${level.prefix} quota exceeded: $used/$limit tokens used"),
        ).asJson).map(_.putHeaders(
          Header.Raw(ci"Retry-After", retryAfter.toString),
        ))

  private def currentTraceId: F[Option[String]] =
    Tracer[F].currentSpanContext.map(
      _.filter(_.isValid).map(_.traceIdHex),
    )

  private def publishQuotaEvent(
      decision: QuotaDecision,
      req: TokenQuotaCheckRequest,
      client: AuthenticatedClient,
      timestamp: Instant,
      traceId: Option[String],
  ): F[Unit] =
    decision match
      case QuotaDecision.Exceeded(level, limit, used, _) =>
        val event = RateLimitEvent.TokenQuotaExceeded(
          timestamp = timestamp,
          userId = req.userId,
          agentId = req.agentId.getOrElse("none"),
          orgId = req.orgId.getOrElse("none"),
          level = level.prefix,
          limit = limit,
          used = used,
          apiKey = client.apiKeyId,
          traceId = traceId,
        )
        val auditEvent = RateLimitEvent.AuditEvent(
          timestamp = timestamp,
          requestId = UUID.randomUUID().toString,
          apiKey = client.apiKeyId,
          clientId = client.apiKeyId,
          decision = "quota_exceeded",
          reason = s"${level.prefix} quota exceeded: $used/$limit tokens",
          endpoint = Some("/v1/quota/check"),
          sourceIp = None,
          tier = None,
          traceId = traceId,
        )
        val publishMain = eventPublisher.publish(event).handleErrorWith(error =>
          logger.warn(s"Failed to publish token quota event: ${error.getMessage}"),
        )
        val publishAudit =
          logger.info(s"AUDIT decision=quota_exceeded user=${req.userId} level=${level.prefix} used=$used/$limit") *>
            eventPublisher.publish(auditEvent).handleErrorWith(error =>
              logger.warn(s"Failed to publish audit event: ${error.getMessage}"),
            )
        publishMain *> publishAudit
      case _ => Async[F].unit

// Request/Response models

case class TokenQuotaCheckRequest(
    userId: String,
    agentId: Option[String] = None,
    orgId: Option[String] = None,
    estimatedInputTokens: Long,
    estimatedOutputTokens: Long = 0,
)

case class TokenQuotaCheckResponse(
    allowed: Boolean,
    remainingTokens: Map[String, Long],
    exceededLevel: Option[String] = None,
    retryAfter: Option[Int] = None,
    message: Option[String] = None,
)

case class TokenQuotaReconcileRequest(
    userId: String,
    agentId: Option[String] = None,
    orgId: Option[String] = None,
    actualInputTokens: Long,
    actualOutputTokens: Long,
    estimatedInputTokens: Long,
    estimatedOutputTokens: Long,
)

case class TokenQuotaReconcileResponse(
    status: String,
    inputDelta: Long,
    outputDelta: Long,
)

object TokenQuotaApi:
  def apply[F[_]: Async: Tracer](
      quotaService: TokenQuotaService[F],
      eventPublisher: EventPublisher[F],
      metricsPublisher: MetricsPublisher[F],
      logger: Logger[F],
  ): TokenQuotaApi[F] =
    new TokenQuotaApi[F](quotaService, eventPublisher, metricsPublisher, logger)