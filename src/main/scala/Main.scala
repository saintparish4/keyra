import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger

import api.Routes
import config.*
import core.*
import events.{BroadcastingEventPublisher, EventPublisher, KinesisPublisher, RateLimitEvent}
import cats.effect.std.Queue
import _root_.metrics.MetricsPublisher
import resilience.*
import security.*
import storage.*

import scala.concurrent.duration.*

object Main extends IOApp:

  override def run(args: List[String]): IO[ExitCode] =
    application.useForever.as(ExitCode.Success)

  private def application: Resource[IO, Server] =
    for
      // Initialize logger
      given Logger[IO] <- Resource.eval(Slf4jLogger.create[IO])
      logger = summon[Logger[IO]]
      _ <- Resource.eval(logger.info("Starting Rate Limiter Platform..."))

      // Load configuration
      config <- Resource.eval(AppConfig.loadOrDefault[IO])
      _ <- Resource.eval(logger.info(s"Configuration loaded: ${config.server.host}:${config.server.port}"))

      // Initialize metrics publisher
      metricsPublisher <- config.metrics.enabled match
        case true => MetricsPublisher.cloudWatch[IO](config.aws.region, config.metrics.namespace)
        case false => Resource.pure[IO, MetricsPublisher[IO]](MetricsPublisher.noop[IO])
      _ <- Resource.eval(logger.info("Metrics publisher initialized"))

      // Initialize event publisher (underlying: Kinesis or noop)
      underlyingEventPublisher <- config.kinesis.enabled match
        case true =>
          for
            kinesisClient <- AwsClients.kinesisClient[IO](config.aws, config.dynamodb)
            kinesisPublisher = new KinesisPublisher[IO](kinesisClient, config.kinesis)
          yield (kinesisPublisher: EventPublisher[IO])
        case false =>
          Resource.pure[IO, EventPublisher[IO]](EventPublisher.noop[IO])
      _ <- Resource.eval(logger.info("Event publisher initialized"))

      // Bounded queue for dashboard SSE; wrap publisher to broadcast events
      dashboardQueue <- Resource.eval(Queue.bounded[IO, RateLimitEvent](512))
      eventPublisher = BroadcastingEventPublisher(underlyingEventPublisher, dashboardQueue)

      // Initialize rate limit store (algorithm + backend)
      rateLimitStore <- (config.aws.localstack, config.rateLimit.algorithm) match
        case (true, _) =>
          Resource.eval(RateLimitStore.inMemory[IO])
        case (false, "leaky-bucket") =>
          for
            dynamoClient <- AwsClients.dynamoDbClient[IO](config.aws, config.dynamodb)
            store = LeakyBucketRateLimitStore[IO](dynamoClient, config.dynamodb.rateLimitTable)
          yield store
        case (false, _) =>
          for
            dynamoClient <- AwsClients.dynamoDbClient[IO](config.aws, config.dynamodb)
            store = new DynamoDBRateLimitStore[IO](dynamoClient, config.dynamodb.rateLimitTable)
          yield store
      _ <- Resource.eval(logger.info(s"Rate limit store initialized: ${config.rateLimit.algorithm}"))

      // Initialize idempotency store
      idempotencyStore <- config.aws.localstack match
        case true =>
          Resource.eval(IdempotencyStore.inMemory[IO])
        case false =>
          for
            dynamoClient <- AwsClients.dynamoDbClient[IO](config.aws, config.dynamodb)
            store = new DynamoDBIdempotencyStore[IO](dynamoClient, config.dynamodb.idempotencyTable)
          yield store
      _ <- Resource.eval(logger.info("Idempotency store initialized"))

      // Initialize API key store
      apiKeyStore <- config.security.secrets.enabled match
        case true =>
          for
            secretsClient <- SecretsManagerStore.clientResource[IO](config.aws)
            secretsConfig = security.SecretsConfig(
              environment = "dev",
              secretPrefix = config.security.secrets.secretPrefix,
              cacheTtl = config.security.secrets.cacheTtl,
              apiKeysSecretName = config.security.secrets.apiKeysSecretName
            )
            secretStore <- Resource.eval(SecretsManagerStore[IO](secretsClient, secretsConfig))
            store <- Resource.eval(SecretsManagerApiKeyStore[IO](secretStore, config.security.secrets.cacheTtl))
          yield store
        case false =>
          Resource.pure[IO, ApiKeyStore[IO]](ApiKeyStore.inMemory[IO](ApiKeyStore.testKeys))
      _ <- Resource.eval(logger.info("API key store initialized"))

      // Create auth rate limiter
      authRateLimiter <- Resource.eval(AuthRateLimiter.inMemory[IO](
        maxRequestsPerMinute = config.security.authentication.rateLimitPerMinute,
        maxFailedAttemptsPerMinute = config.security.authentication.maxFailedAttempts
      ))

      // Create authentication middleware
      authMiddleware = ApiKeyAuth.middleware[IO](apiKeyStore, Some(authRateLimiter))

      // Create HTTP routes (dashboard SSE receives events via dashboardQueue)
      routes <- Resource.eval(Routes[IO](
        rateLimitStore,
        idempotencyStore,
        eventPublisher,
        metricsPublisher,
        authMiddleware,
        config.rateLimit,
        logger,
        Some(dashboardQueue),
      ))
      _ <- Resource.eval(logger.info("HTTP routes initialized"))

      // Start server
      server <- EmberServerBuilder
        .default[IO]
        .withHost(Host.fromString(config.server.host).getOrElse(host"0.0.0.0"))
        .withPort(Port.fromInt(config.server.port).getOrElse(port"8080"))
        .withHttpApp(routes.httpApp)
        .withShutdownTimeout(config.server.shutdownTimeout)
        .build
      
      _ <- Resource.eval(logger.info(s"Server started on ${config.server.host}:${config.server.port}"))
    yield server
