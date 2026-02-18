package api

import java.time.Instant

import org.http4s.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.Http4sDsl
import org.http4s.server.{AuthMiddleware, Router}
import org.typelevel.log4cats.Logger

import cats.effect.*
import cats.effect.std.Queue
import cats.syntax.all.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import core.*
import events.*
import _root_.metrics.MetricsPublisher
import security.*
import config.{IdempotencyConfig, RateLimitConfig}

/** HTTP routes for the rate limiter API.
  *
  * Provides endpoints for:
  *   - Rate limit checks
  *   - Idempotency checks
  *   - Health and readiness probes
  *   - Metrics (admin only)
  */
class Routes[F[_]: Async](
    rateLimitStore: RateLimitStore[F],
    idempotencyStore: IdempotencyStore[F],
    eventPublisher: EventPublisher[F],
    metricsPublisher: MetricsPublisher[F],
    authMiddleware: AuthMiddleware[F, AuthenticatedClient],
    rateLimitConfig: RateLimitConfig,
    idempotencyConfig: IdempotencyConfig,
    logger: Logger[F],
    dashboardApi: DashboardApi[F],
) extends Http4sDsl[F]:

  private val rateLimitApi = RateLimitApi[F](
    rateLimitStore,
    eventPublisher,
    metricsPublisher,
    rateLimitConfig,
    logger,
  )

  private val idempotencyApi = IdempotencyApi[F](
    idempotencyStore,
    idempotencyConfig,
    eventPublisher,
    metricsPublisher,
    logger,
  )

  // Public routes (no auth required)
  private val publicRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    // Liveness probe - always returns 200 if service is running
    case GET -> Root / "health" =>
      Ok(HealthResponse("healthy", BuildInfo.version).asJson)

    // Readiness probe - checks dependencies
    case GET -> Root / "ready" =>
      for
        dynamoRlResult <- rateLimitStore.healthCheck
          .handleError(e => Left(e.getMessage))
        kinesisResult <- eventPublisher.healthCheck
          .handleError(e => Left(e.getMessage))
        dynamoIdResult <- idempotencyStore.healthCheck
          .handleError(e => Left(e.getMessage))

        failures = List(
          dynamoRlResult.left.toOption.map(r => s"DynamoDB (rate-limit): $r"),
          kinesisResult.left.toOption.map(r => s"Kinesis: $r"),
          dynamoIdResult.left.toOption.map(r => s"DynamoDB (idempotency): $r"),
        ).flatten

        _ <- failures
          .traverse_(reason => logger.warn(s"Readiness check failed: $reason"))

        checks = Map(
          "dynamodb_ratelimit" -> dynamoRlResult.isRight,
          "dynamodb_idempotency" -> dynamoIdResult.isRight,
          "kinesis" -> kinesisResult.isRight,
        )

        response <-
          if failures.isEmpty then Ok(ReadyResponse("ready", checks).asJson)
          else
            ServiceUnavailable(
              ReadyResponse("not ready", checks, Some(failures)).asJson,
            )
      yield response
  }

  // Authenticated routes
  private val authedRoutes: AuthedRoutes[AuthenticatedClient, F] = AuthedRoutes
    .of {
      // Rate limit check
      case req @ POST -> Root / "v1" / "ratelimit" / "check" as client =>
        rateLimitApi.check(req.req, client)

      // Rate limit status
      case GET -> Root / "v1" / "ratelimit" / "status" / key as client =>
        rateLimitApi.status(key, client)

      // Idempotency check
      case req @ POST -> Root / "v1" / "idempotency" / "check" as client =>
        idempotencyApi.check(req.req, client)

      // Store idempotency response
      case req @ POST -> Root / "v1" / "idempotency" / key / "complete" as
          client => idempotencyApi.complete(key, req.req, client)
    }

  // Combined routes — dashboard is public, API routes require auth
  val routes: HttpRoutes[F] = dashboardApi.routes <+> publicRoutes <+>
    authMiddleware(authedRoutes)

  def httpApp: HttpApp[F] = Router("/" -> routes).orNotFound

// API models
case class HealthResponse(status: String, version: String)
case class ReadyResponse(
    status: String,
    checks: Map[String, Boolean],
    failing: Option[List[String]] = None,
)

object BuildInfo:
  val version = "0.2.0"

object Routes:
  def apply[F[_]: Async](
      rateLimitStore: RateLimitStore[F],
      idempotencyStore: IdempotencyStore[F],
      eventPublisher: EventPublisher[F],
      metricsPublisher: MetricsPublisher[F],
      authMiddleware: AuthMiddleware[F, AuthenticatedClient],
      rateLimitConfig: RateLimitConfig,
      idempotencyConfig: IdempotencyConfig,
      logger: Logger[F],
      dashboardEventQueue: Option[Queue[F, RateLimitEvent]] = None,
  ): F[Routes[F]] =
    for
      dashboardApi <- DashboardApi.apply[F](
        rateLimitStore,
        rateLimitConfig,
        logger,
        dashboardEventQueue,
        eventPublisher,
      )
      routes = new Routes[F](
        rateLimitStore,
        idempotencyStore,
        eventPublisher,
        metricsPublisher,
        authMiddleware,
        rateLimitConfig,
        idempotencyConfig,
        logger,
        dashboardApi,
      )
    yield routes
