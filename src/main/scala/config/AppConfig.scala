package config

import cats.effect.Sync
import cats.syntax.all.*
import pureconfig.*
import pureconfig.generic.derivation.default.*
import scala.concurrent.duration.FiniteDuration

// Server configuration
case class ServerConfig(
    host: String, 
    port: Int,
    shutdownTimeout: FiniteDuration = scala.concurrent.duration.Duration(30, "seconds")
) derives ConfigReader

// AWS configuration for SDK clients
case class AwsConfig(
    region: String,
    localstack: Boolean,
    endpoint: String = "",
    dynamodbEndpoint: Option[String] = None,
    kinesisEndpoint: Option[String] = None,
) derives ConfigReader

// DynamoDB table configuration
case class DynamoDBConfig(
    rateLimitTable: String, 
    idempotencyTable: String,
    connectionTimeout: FiniteDuration = scala.concurrent.duration.Duration(5, "seconds"),
    requestTimeout: FiniteDuration = scala.concurrent.duration.Duration(10, "seconds")
) derives ConfigReader

// Kinesis stream configuration
case class KinesisConfig(
    streamName: String, 
    enabled: Boolean,
    batchSize: Int = 100,
    flushInterval: FiniteDuration = scala.concurrent.duration.Duration(1, "second")
) derives ConfigReader

// Metrics configuration
case class MetricsConfig(
    enabled: Boolean = true,
    namespace: String = "RateLimiter"
) derives ConfigReader

// Security configuration
case class AuthenticationConfig(
    enabled: Boolean = true,
    rateLimitPerMinute: Int = 1000,
    maxFailedAttempts: Int = 10
) derives ConfigReader

case class SecretsConfig(
    enabled: Boolean = false,
    secretPrefix: String = "rate-limiter",
    apiKeysSecretName: String = "api-keys",
    cacheTtl: FiniteDuration = scala.concurrent.duration.Duration(5, "minutes")
) derives ConfigReader

case class SecurityConfig(
    authentication: AuthenticationConfig,
    secrets: SecretsConfig
) derives ConfigReader

// Rate limiting profile
case class RateLimitProfileConfig(
    capacity: Int,
    refillRatePerSecond: Double,
    ttlSeconds: Long
) derives ConfigReader

// Rate limiting defaults
case class RateLimitConfig(
    defaultCapacity: Int,
    defaultRefillRatePerSecond: Double,
    defaultTtlSeconds: Long,
    algorithm: String = "token-bucket",
    profiles: Map[String, RateLimitProfileConfig] = Map.empty
) derives ConfigReader

// Resilience configuration
case class CircuitBreakerConfig(
    maxFailures: Int = 5,
    resetTimeout: FiniteDuration = scala.concurrent.duration.Duration(30, "seconds"),
    halfOpenMaxCalls: Int = 3
) derives ConfigReader

case class CircuitBreakerSettings(
    enabled: Boolean = true,
    dynamodb: CircuitBreakerConfig = CircuitBreakerConfig(),
    kinesis: CircuitBreakerConfig = CircuitBreakerConfig()
) derives ConfigReader

case class RetryConfig(
    maxRetries: Int = 3,
    baseDelay: FiniteDuration = scala.concurrent.duration.Duration(100, "millis"),
    maxDelay: FiniteDuration = scala.concurrent.duration.Duration(10, "seconds"),
    multiplier: Double = 2.0
) derives ConfigReader

case class RetrySettings(
    dynamodb: RetryConfig = RetryConfig(),
    kinesis: RetryConfig = RetryConfig()
) derives ConfigReader

case class BulkheadSettings(
    enabled: Boolean = true,
    maxConcurrent: Int = 25,
    maxWait: FiniteDuration = scala.concurrent.duration.Duration(100, "millis")
) derives ConfigReader

case class TimeoutSettings(
    rateLimitCheck: FiniteDuration = scala.concurrent.duration.Duration(500, "millis"),
    healthCheck: FiniteDuration = scala.concurrent.duration.Duration(5, "seconds")
) derives ConfigReader

case class ResilienceConfig(
    circuitBreaker: CircuitBreakerSettings = CircuitBreakerSettings(),
    retry: RetrySettings = RetrySettings(),
    bulkhead: BulkheadSettings = BulkheadSettings(),
    timeout: TimeoutSettings = TimeoutSettings()
) derives ConfigReader

// Root application configuration
case class AppConfig(
    server: ServerConfig,
    aws: AwsConfig,
    dynamodb: DynamoDBConfig,
    kinesis: KinesisConfig,
    rateLimit: RateLimitConfig,
    metrics: MetricsConfig = MetricsConfig(),
    security: SecurityConfig = SecurityConfig(
      authentication = AuthenticationConfig(),
      secrets = SecretsConfig()
    )
) derives ConfigReader

object AppConfig:
  def load[F[_]: Sync]: F[AppConfig] = Sync[F]
    .delay(ConfigSource.default.loadOrThrow[AppConfig])
  
  def loadOrDefault[F[_]: Sync]: F[AppConfig] = 
    load[F].handleErrorWith(_ => 
      Sync[F].pure(AppConfig(
        server = ServerConfig("0.0.0.0", 8080),
        aws = AwsConfig("us-east-1", false, ""),
        dynamodb = DynamoDBConfig("rate-limits", "idempotency"),
        kinesis = KinesisConfig("rate-limit-events", false),
        rateLimit = RateLimitConfig(100, 10.0, 3600, "token-bucket")
      ))
    )
