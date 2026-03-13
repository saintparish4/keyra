package config

import scala.concurrent.duration.FiniteDuration

import cats.effect.Sync
import cats.syntax.all.*
import pureconfig.*
import pureconfig.generic.derivation.default.*

// Server configuration
case class ServerConfig(
    host: String,
    port: Int,
    shutdownTimeout: FiniteDuration = scala.concurrent.duration
      .Duration(30, "seconds"),
) derives ConfigReader

// AWS configuration for SDK clients
/** HTTP connection pool settings for each AWS SDK client. These are passed
  * directly to NettyNioAsyncHttpClient.
  */
case class AwsClientConfig(
    maxConnections: Int = 50,
    maxPendingAcquires: Int = 100,
    connectionAcquisitionTimeoutSeconds: Int = 5,
    connectionTimeToLiveSeconds: Int = 60,
    connectionMaxIdleSeconds: Int = 5,
    disableSdkRetries: Boolean = true,
) derives ConfigReader

case class AwsConfig(
    region: String,
    localstack: Boolean,
    endpoint: String = "",
    dynamodbEndpoint: Option[String] = None,
    kinesisEndpoint: Option[String] = None,
    client: AwsClientConfig = AwsClientConfig(),
) derives ConfigReader

// DynamoDB table configuration
case class DynamoDBConfig(
    rateLimitTable: String,
    idempotencyTable: String,
    connectionTimeout: FiniteDuration = scala.concurrent.duration
      .Duration(5, "seconds"),
    requestTimeout: FiniteDuration = scala.concurrent.duration
      .Duration(10, "seconds"),
) derives ConfigReader

// Kinesis stream configuration
case class KinesisConfig(
    streamName: String,
    enabled: Boolean,
    batchSize: Int = 100,
    flushInterval: FiniteDuration = scala.concurrent.duration
      .Duration(1, "second"),
    queueSize: Int = 10000,
) derives ConfigReader

// Metrics configuration
case class MetricsConfig(
    enabled: Boolean = true,
    namespace: String = "RateLimiter",
    maxBufferSize: Int = 50000,
    flushThreshold: Int = 1000,
) derives ConfigReader

case class PrometheusConfig(enabled: Boolean = true) derives ConfigReader

case class TracingConfig(
    enabled: Boolean = false,
    serviceName: String = "keyra",
    exporterEndpoint: String = "http://localhost:4317",
) derives ConfigReader

// Security configuration
case class AuthenticationConfig(
    enabled: Boolean = true,
    rateLimitPerMinute: Int = 1000,
    maxFailedAttempts: Int = 10,
) derives ConfigReader

case class SecretsConfig(
    enabled: Boolean = false,
    secretPrefix: String = "rate-limiter",
    apiKeysSecretName: String = "api-keys",
    cacheTtl: FiniteDuration = scala.concurrent.duration.Duration(5, "minutes"),
) derives ConfigReader

case class SecurityConfig(
    authentication: AuthenticationConfig,
    secrets: SecretsConfig,
) derives ConfigReader

// Rate limiting profile
case class RateLimitProfileConfig(
    capacity: Int,
    refillRatePerSecond: Double,
    ttlSeconds: Long,
) derives ConfigReader:
  /** Validate profile fields; called at config load time so bad config fails
    * startup rather than silently producing wrong runtime behaviour.
    */
  def validate(name: String): Either[String, Unit] =
    if name.isEmpty then Left("profile name cannot be empty")
    else if capacity < 1 then
      Left(s"profile '$name': capacity must be >= 1, got $capacity")
    else if refillRatePerSecond <= 0 then
      Left(s"profile '$name': refillRatePerSecond must be > 0, got $refillRatePerSecond")
    else Right(())

// Rate limiting defaults
case class RateLimitConfig(
    defaultCapacity: Int,
    defaultRefillRatePerSecond: Double,
    defaultTtlSeconds: Long,
    algorithm: String = "token-bucket",
    profiles: Map[String, RateLimitProfileConfig] = Map.empty,
) derives ConfigReader

// Idempotency TTL: default and max cap for client-supplied TTL
case class IdempotencyConfig(
    defaultTtlSeconds: Long = 86400,
    maxTtlSeconds: Long = 86400,
) derives ConfigReader

// Token quota limits for AI workloads (per-user, per-agent, per-org)
case class TokenQuotaConfig(
    enabled: Boolean = false,
    tableName: String = "keyra-token-quotas",
    userLimit: Long = 1_000_000,
    userWindowSeconds: Long = 3600,
    agentLimit: Long = 500_000,
    agentWindowSeconds: Long = 3600,
    orgLimit: Long = 10_000_000,
    orgWindowSeconds: Long = 86400,
) derives ConfigReader
// Resilience configuration
case class CircuitBreakerConfig(
    maxFailures: Int = 5,
    resetTimeout: FiniteDuration = scala.concurrent.duration
      .Duration(30, "seconds"),
    halfOpenMaxCalls: Int = 3,
) derives ConfigReader

case class CircuitBreakerSettings(
    enabled: Boolean = true,
    dynamodb: CircuitBreakerConfig = CircuitBreakerConfig(),
    kinesis: CircuitBreakerConfig = CircuitBreakerConfig(),
) derives ConfigReader

case class RetryConfig(
    maxRetries: Int = 3,
    baseDelay: FiniteDuration = scala.concurrent.duration.Duration(100, "millis"),
    maxDelay: FiniteDuration = scala.concurrent.duration.Duration(10, "seconds"),
    multiplier: Double = 2.0,
) derives ConfigReader

case class RetrySettings(
    dynamodb: RetryConfig = RetryConfig(),
    kinesis: RetryConfig = RetryConfig(),
) derives ConfigReader

case class BulkheadSettings(
    enabled: Boolean = true,
    maxConcurrent: Int = 25,
    maxWait: FiniteDuration = scala.concurrent.duration.Duration(100, "millis"),
) derives ConfigReader

case class TimeoutSettings(
    rateLimitCheck: FiniteDuration = scala.concurrent.duration
      .Duration(500, "millis"),
    healthCheck: FiniteDuration = scala.concurrent.duration
      .Duration(5, "seconds"),
) derives ConfigReader

case class ResilienceConfig(
    circuitBreaker: CircuitBreakerSettings = CircuitBreakerSettings(),
    retry: RetrySettings = RetrySettings(),
    bulkhead: BulkheadSettings = BulkheadSettings(),
    timeout: TimeoutSettings = TimeoutSettings(),
    degradationMode: String = "reject-all",
) derives ConfigReader:
  import resilience.GracefulDegradation.DegradationMode
  def parsedDegradationMode: DegradationMode = degradationMode match
    case "allow-all" => DegradationMode.AllowAll
    case "reject-all" => DegradationMode.RejectAll
    case "use-cached" => DegradationMode.UseCached
    case _ => DegradationMode.RejectAll

case class StorageConfig(
    backend: String = "dynamodb", // "in-memory" | "dynamodb"
) derives ConfigReader

/** Audit trail configuration for PCI DSS 4.0.1 compliance. */
case class AuditConfig(
    enabled: Boolean = true,
    retentionYears: Int = 7,
    s3Prefix: String = "audit/",
) derives ConfigReader

// Root application configuration
case class AppConfig(
    server: ServerConfig,
    aws: AwsConfig,
    tokenQuota: TokenQuotaConfig = TokenQuotaConfig(),
    dynamodb: DynamoDBConfig,
    kinesis: KinesisConfig,
    rateLimit: RateLimitConfig,
    idempotency: IdempotencyConfig = IdempotencyConfig(),
    metrics: MetricsConfig = MetricsConfig(),
    prometheus: PrometheusConfig = PrometheusConfig(),
    tracing: TracingConfig = TracingConfig(),
    security: SecurityConfig = SecurityConfig(
      authentication = AuthenticationConfig(),
      secrets = SecretsConfig(),
    ),
    resilience: ResilienceConfig = ResilienceConfig(),
    storage: StorageConfig = StorageConfig(),
    audit: AuditConfig = AuditConfig(),
) derives ConfigReader

object AppConfig:
  def load[F[_]: Sync]: F[AppConfig] = Sync[F]
    .delay(ConfigSource.default.loadOrThrow[AppConfig]).flatMap { config =>
      val profileErrors = config.rateLimit.profiles.toList
        .flatMap { case (name, p) => p.validate(name).left.toOption }
      val agentCap = (config.tokenQuota.userLimit * 0.8).toLong
      val quotaErrors =
        if config.tokenQuota.enabled && config.tokenQuota.agentLimit > agentCap
        then
          List(s"agentLimit (${config.tokenQuota
              .agentLimit}) exceeds 80% of userLimit ($agentCap)")
        else Nil
      val allErrors = profileErrors ++ quotaErrors
      if allErrors.nonEmpty then
        Sync[F]
          .raiseError(new IllegalArgumentException(s"Invalid config: ${allErrors
              .mkString("; ")}"))
      else Sync[F].pure(config)
    }

  def loadOrDefault[F[_]: Sync]: F[AppConfig] = load[F].handleErrorWith(_ =>
    Sync[F].pure(AppConfig(
      server = ServerConfig("0.0.0.0", 8080),
      aws = AwsConfig("us-east-1", false, ""),
      dynamodb = DynamoDBConfig("rate-limits", "idempotency"),
      kinesis = KinesisConfig("rate-limit-events", false),
      rateLimit = RateLimitConfig(100, 10.0, 3600, "token-bucket"),
      idempotency = IdempotencyConfig(),
    )),
  )
