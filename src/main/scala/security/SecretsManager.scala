package security

import scala.concurrent.duration.*
import scala.jdk.FutureConverters.*

import org.typelevel.log4cats.Logger

import cats.effect.*
import cats.syntax.all.*
import io.circe.*
import io.circe.generic.auto.*
import io.circe.parser.*
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient
import software.amazon.awssdk.services.secretsmanager.model.*

/** AWS Secrets Manager integration for secure credential storage.
  *
  * Provides:
  *   - Secure API key storage and retrieval
  *   - Automatic secret caching with TTL
  *   - Secret rotation support
  *   - Environment-aware secret naming
  */

/** Secret store trait for retrieving secrets.
  */
trait SecretStore[F[_]]:
  /** Get a secret value by name */
  def getSecret(secretName: String): F[Option[String]]

  /** Get a secret as JSON and parse it */
  def getSecretAs[A: Decoder](secretName: String): F[Option[A]]

  /** Get API keys from secrets */
  def getApiKeys: F[Map[String, AuthenticatedClient]]

/** Configuration for Secrets Manager.
  */
case class SecretsConfig(
    environment: String = "dev",
    secretPrefix: String = "rate-limiter",
    cacheTtl: FiniteDuration = 5.minutes,
    apiKeysSecretName: String = "api-keys",
):
  def fullSecretName(name: String): String = s"$secretPrefix/$environment/$name"

object SecretsConfig:
  val default: SecretsConfig = SecretsConfig()

/** Secret value with metadata.
  */
private case class CachedSecret(
    value: String,
    cachedAt: Long,
    versionId: Option[String],
)

/** API key configuration stored in Secrets Manager.
  */
case class ApiKeyConfig(
    apiKey: String,
    apiKeyId: String,
    clientName: String,
    tier: String,
    permissions: List[String],
    active: Boolean = true,
)

/** AWS Secrets Manager implementation of SecretStore.
  */
object SecretsManagerStore:

  /** Create a Secrets Manager client as a Resource.
    */
  def clientResource[F[_]: Async](
      awsConfig: _root_.config.AwsConfig,
  ): Resource[F, SecretsManagerAsyncClient] =
    import software.amazon.awssdk.regions.Region
    import software.amazon.awssdk.auth.credentials.*
    import java.net.URI

    Resource.make(Async[F].delay {
      val builder = SecretsManagerAsyncClient.builder()
        .region(Region.of(awsConfig.region))

      if awsConfig.endpoint.nonEmpty then
        builder.endpointOverride(URI.create(awsConfig.endpoint))
          .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create("test", "test"),
          ))

      builder.build()
    })(client => Async[F].delay(client.close()))

  /** Create a SecretStore backed by AWS Secrets Manager.
    */
  def apply[F[_]: Async: Logger](
      client: SecretsManagerAsyncClient,
      config: SecretsConfig = SecretsConfig.default,
  ): F[SecretStore[F]] =
    for cacheRef <- Ref.of[F, Map[String, CachedSecret]](Map.empty)
    yield new SecretStore[F]:
      private val logger = Logger[F]

      override def getSecret(secretName: String): F[Option[String]] =
        val fullName = config.fullSecretName(secretName)

        // Check cache first
        cacheRef.get.flatMap(cache =>
          cache.get(fullName) match
            case Some(cached) => Clock[F].realTime.map(_.toMillis)
                .flatMap(now =>
                  if now - cached.cachedAt < config.cacheTtl.toMillis then
                    logger.debug(s"Cache hit for secret: $fullName") *>
                      Async[F].pure(Some(cached.value))
                  else fetchAndCache(fullName),
                )
            case None => fetchAndCache(fullName),
        )

      override def getSecretAs[A: Decoder](secretName: String): F[Option[A]] =
        getSecret(secretName).flatMap {
          case Some(json) => decode[A](json) match
              case Right(value) => Async[F].pure(Some(value))
              case Left(error) => logger
                  .error(s"Failed to parse secret $secretName: ${error
                      .getMessage}") *> Async[F].pure(None)
          case None => Async[F].pure(None)
        }

      override def getApiKeys: F[Map[String, AuthenticatedClient]] =
        getSecretAs[List[ApiKeyConfig]](config.apiKeysSecretName).map {
          case Some(configs) => configs.filter(_.active).flatMap(cfg =>
              ClientTier.fromString(cfg.tier).map(tier =>
                cfg.apiKey -> AuthenticatedClient(
                  apiKeyId = cfg.apiKeyId,
                  clientId = cfg.apiKeyId,
                  clientName = cfg.clientName,
                  tier = tier,
                  permissions = cfg.permissions.flatMap(parsePermission).toSet,
                ),
              ),
            ).toMap
          case None =>
            // Fall back to test keys in development
            if config.environment == "dev" then
              logger
                .warn("No API keys found in Secrets Manager, using test keys")
              ApiKeyStore.testKeys
            else Map.empty
        }

      private def fetchAndCache(fullName: String): F[Option[String]] =
        val request = GetSecretValueRequest.builder().secretId(fullName).build()

        Async[F]
          .fromCompletableFuture(Async[F].delay(client.getSecretValue(request)))
          .flatMap { response =>
            val value = response.secretString()
            val versionId = Option(response.versionId())

            Clock[F].realTime.map(_.toMillis).flatMap(now =>
              cacheRef.update(
                _ + (fullName -> CachedSecret(value, now, versionId)),
              ) *> logger.debug(s"Cached secret: $fullName (version: ${versionId
                  .getOrElse("unknown")})") *> Async[F].pure(Some(value)),
            )
          }.handleErrorWith(error =>
            error match
              case _: ResourceNotFoundException => logger
                  .warn(s"Secret not found: $fullName") *> Async[F].pure(None)
              case e => logger.error(e)(s"Failed to fetch secret: $fullName") *>
                  Async[F].raiseError(e),
          )

      private def parsePermission(s: String): Option[Permission] =
        s.toLowerCase match
          case "ratelimit_check" | "ratelimitcheck" =>
            Some(Permission.RateLimitCheck)
          case "ratelimit_status" | "ratelimitstatus" =>
            Some(Permission.RateLimitStatus)
          case "idempotency_check" | "idempotencycheck" =>
            Some(Permission.IdempotencyCheck)
          case "admin_metrics" | "adminmetrics" => Some(Permission.AdminMetrics)
          case "admin_config" | "adminconfig" => Some(Permission.AdminConfig)
          case _ => None

/** API key store backed by Secrets Manager.
  */
object SecretsManagerApiKeyStore:

  /** Create an API key store that loads keys from Secrets Manager.
    */
  def apply[F[_]: Async: Logger](
      secretStore: SecretStore[F],
      refreshInterval: FiniteDuration = 5.minutes,
  ): F[ApiKeyStore[F]] =
    for
      keysRef <- Ref.of[F, Map[String, AuthenticatedClient]](Map.empty)
      lastRefreshRef <- Ref.of[F, Long](0L)
    yield new ApiKeyStore[F]:
      private val logger = Logger[F]

      override def findByKey(apiKey: String): F[Option[AuthenticatedClient]] =
        maybeRefresh *> keysRef.get.map(_.get(apiKey))

      override def isKeyValid(apiKey: String): F[Boolean] = maybeRefresh *>
        keysRef.get.map(_.contains(apiKey))

      private def maybeRefresh: F[Unit] =
        for
          now <- Clock[F].realTime.map(_.toMillis)
          lastRefresh <- lastRefreshRef.get
          _ <-
            if now - lastRefresh > refreshInterval.toMillis then
              refresh *> lastRefreshRef.set(now)
            else Async[F].unit
        yield ()

      private def refresh: F[Unit] = secretStore.getApiKeys.flatMap(keys =>
        keysRef.set(keys) *>
          logger.info(s"Refreshed ${keys.size} API keys from Secrets Manager"),
      ).handleErrorWith(error =>
        logger.error(error)("Failed to refresh API keys, keeping existing") *>
          Async[F].unit,
      )
