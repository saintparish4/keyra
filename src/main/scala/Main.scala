import scala.concurrent.duration.*

import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import com.comcast.ip4s.*

import cats.effect.*
import cats.syntax.all.*
import api.Routes
import config.*
import core.*
import events.{
  BroadcastingEventPublisher, EventPublisher, KinesisPublisher, RateLimitEvent,
}
import cats.effect.std.Queue
import _root_.metrics.MetricsPublisher
import resilience.*
import security.*
import storage.*

object Main extends IOApp:

  override def run(args: List[String]): IO[ExitCode] = application.useForever
    .as(ExitCode.Success)

  private def application: Resource[IO, Server] =
    for
      // Initialize logger
      given Logger[IO] <- Resource.eval(Slf4jLogger.create[IO])
      logger = summon[Logger[IO]]
      _ <- Resource.eval(logger.info("Starting Rate Limiter Platform..."))

      // Load configuration
      config <- Resource.eval(AppConfig.loadOrDefault[IO])
      _ <- Resource.eval(logger.info(s"Configuration loaded: ${config.server
          .host}:${config.server.port}"))

      // Initialize metrics publisher — pass full config so flush-threshold and
      // max-buffer-size are wired from application.conf
      metricsPublisher <- config.metrics.enabled match
        case true =>
          val metricsConfig = _root_.metrics.MetricsConfig(
            namespace = config.metrics.namespace,
            maxBufferSize = config.metrics.maxBufferSize,
            flushThreshold = config.metrics.flushThreshold,
          )
          MetricsPublisher.cloudWatch[IO](config.aws.region, metricsConfig)
        case false => Resource
            .pure[IO, MetricsPublisher[IO]](MetricsPublisher.noop[IO])
      _ <- Resource.eval(logger.info("Metrics publisher initialized"))

      // Initialize event publisher — Kinesis uses a bounded queue + drain fiber
      // managed by KinesisPublisher.resource so the fiber is properly cancelled on shutdown
      underlyingEventPublisher <- config.kinesis.enabled match
        case true =>
          for
            kinesisClient <- AwsClients
              .kinesisClient[IO](config.aws, config.dynamodb)
            publisher <- KinesisPublisher
              .resource[IO](kinesisClient, config.kinesis, metricsPublisher)
          yield publisher
        case false => Resource
            .pure[IO, EventPublisher[IO]](EventPublisher.noop[IO])
      _ <- Resource.eval(logger.info("Event publisher initialized"))

      // Bounded queue for dashboard SSE; wrap publisher to broadcast events
      dashboardQueue <- Resource.eval(Queue.bounded[IO, RateLimitEvent](512))
      eventPublisher =
        BroadcastingEventPublisher(underlyingEventPublisher, dashboardQueue)

      // Initialize rate limit store (algorithm + backend)
      rateLimitStore <- config.storage.backend match
        case "in-memory" => Resource.eval(RateLimitStore.inMemory[IO])
        case _ => config.rateLimit.algorithm match
            case "leaky-bucket" =>
              for
                dynamoClient <- AwsClients
                  .dynamoDbClient[IO](config.aws, config.dynamodb)
                store = LeakyBucketRateLimitStore[IO](
                  dynamoClient,
                  config.dynamodb.rateLimitTable,
                  logger,
                  metricsPublisher,
                )
              yield store
            case _ =>
              for
                dynamoClient <- AwsClients
                  .dynamoDbClient[IO](config.aws, config.dynamodb)
                store = new DynamoDBRateLimitStore[IO](
                  dynamoClient,
                  config.dynamodb.rateLimitTable,
                  logger,
                  metricsPublisher,
                )
              yield store
      _ <- Resource.eval(logger.info(s"Rate limit store initialized: ${config
          .rateLimit.algorithm}"))

      // Wrap with resilience patterns (circuit breaker -> bulkhead -> retry)
      resilientStore <- ResilientRateLimitStore[IO](
        rateLimitStore,
        config.resilience,
        metricsPublisher,
        eventPublisher,
        config.resilience.parsedDegradationMode,
      )
      _ <- Resource.eval(logger.info("Resilient rate limit store initialized"))

      // Initialize idempotency store
      idempotencyStore <- config.storage.backend match
        case "in-memory" => Resource.eval(IdempotencyStore.inMemory[IO])
        case _ =>
          for
            dynamoClient <- AwsClients
              .dynamoDbClient[IO](config.aws, config.dynamodb)
            store = new DynamoDBIdempotencyStore[IO](
              dynamoClient,
              config.dynamodb.idempotencyTable,
            )
          yield store
      _ <- Resource.eval(logger.info("Idempotency store initialized"))

      // Initialize token quota store + service (if enabled)
      tokenQuotaApi <- config.tokenQuota.enabled match
        case true => config.storage.backend match
            case "in-memory" => Resource.eval {
                Ref.of[IO, Map[String, core.TokenQuotaState]](Map.empty).map {
                  ref =>
                    val inMemStore = new core.TokenQuotaStore[IO]:
                      override def getQuota(
                          pk: String,
                      ): IO[Option[core.TokenQuotaState]] = ref.get.map(_.get(pk))
                      override def incrementQuota(
                          pk: String,
                          inputDelta: Long,
                          outputDelta: Long,
                          windowSec: Long,
                          nowMs: Long,
                      ): IO[Boolean] = ref.modify { m =>
                        val current = m.get(pk)
                        val withinWindow = current
                          .exists(s => nowMs - s.windowStart < windowSec * 1000)
                        if withinWindow then
                          val s = current.get
                          val updated = core.TokenQuotaState(
                            math.max(0, s.inputTokens + inputDelta),
                            math.max(0, s.outputTokens + outputDelta),
                            s.windowStart,
                            s.version + 1,
                          )
                          (m + (pk -> updated), true)
                        else
                          val fresh = core.TokenQuotaState(
                            math.max(0, inputDelta),
                            math.max(0, outputDelta),
                            nowMs,
                            1L,
                          )
                          (m + (pk -> fresh), true)
                      }
                      override def healthCheck: IO[Either[String, Unit]] = IO
                        .pure(Right(()))
                    val svc = core.TokenQuotaService[IO](
                      inMemStore,
                      config.tokenQuota,
                      metricsPublisher,
                      logger,
                    )
                    Some(api.TokenQuotaApi[IO](
                      svc,
                      eventPublisher,
                      metricsPublisher,
                      logger,
                    ))
                }
              }
            case _ =>
              for
                dynamoClient <- AwsClients
                  .dynamoDbClient[IO](config.aws, config.dynamodb)
                store = storage.DynamoDBTokenQuotaStore[IO](
                  dynamoClient,
                  config.tokenQuota.tableName,
                  logger,
                  metricsPublisher,
                )
                svc = core.TokenQuotaService[IO](
                  store,
                  config.tokenQuota,
                  metricsPublisher,
                  logger,
                )
              yield Some(api.TokenQuotaApi[IO](
                svc,
                eventPublisher,
                metricsPublisher,
                logger,
              ))
        case false => Resource.pure[IO, Option[api.TokenQuotaApi[IO]]](None)
      _ <- Resource
        .eval(logger.info(s"Token quota service initialized: enabled=${config
            .tokenQuota.enabled}"))

      // Initialize API key store
      apiKeyStore <- config.security.secrets.enabled match
        case true =>
          for
            secretsClient <- SecretsManagerStore.clientResource[IO](config.aws)
            secretsConfig = security.SecretsConfig(
              environment = "dev",
              secretPrefix = config.security.secrets.secretPrefix,
              cacheTtl = config.security.secrets.cacheTtl,
              apiKeysSecretName = config.security.secrets.apiKeysSecretName,
            )
            secretStore <- Resource
              .eval(SecretsManagerStore[IO](secretsClient, secretsConfig))
            store <- Resource.eval(SecretsManagerApiKeyStore[IO](
              secretStore,
              config.security.secrets.cacheTtl,
            ))
          yield store
        case false => Resource.pure[IO, ApiKeyStore[IO]](
            ApiKeyStore.inMemory[IO](ApiKeyStore.testKeys),
          )
      _ <- Resource.eval(logger.info("API key store initialized"))

      // Create auth rate limiter
      authRateLimiter <- Resource.eval(AuthRateLimiter.inMemory[IO](
        maxRequestsPerMinute = config.security.authentication.rateLimitPerMinute,
        maxFailedAttemptsPerMinute =
          config.security.authentication.maxFailedAttempts,
      ))

      // Create authentication middleware
      authMiddleware = ApiKeyAuth
        .middleware[IO](apiKeyStore, Some(authRateLimiter))

      // Create HTTP routes (dashboard SSE receives events via dashboardQueue)
      routes <- Resource.eval(Routes[IO](
        rateLimitStore,
        idempotencyStore,
        eventPublisher,
        metricsPublisher,
        authMiddleware,
        config.rateLimit,
        config.idempotency,
        logger,
        Some(dashboardQueue),
        tokenQuotaApi,
      ))
      _ <- Resource.eval(logger.info("HTTP routes initialized"))

      // Start server
      server <- EmberServerBuilder.default[IO]
        .withHost(Host.fromString(config.server.host).getOrElse(host"0.0.0.0"))
        .withPort(Port.fromInt(config.server.port).getOrElse(port"8080"))
        .withHttpApp(routes.httpApp)
        .withShutdownTimeout(config.server.shutdownTimeout).build

      _ <- Resource.eval(logger.info(s"Server started on ${config.server
          .host}:${config.server.port}"))
    yield server
