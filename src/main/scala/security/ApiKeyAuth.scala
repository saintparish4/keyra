package security

import scala.concurrent.duration.*

import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.server.AuthMiddleware
import org.typelevel.ci.*
import org.typelevel.log4cats.Logger

import cats.data.{Kleisli, OptionT}
import cats.effect.*
import cats.syntax.all.*

/** API key authentication for securing rate limiter endpoints.
  *
  * Provides:
  *   - API key validation from Authorization header
  *   - Rate limiting on the rate limiter itself (meta!)
  *   - Client identification for per-client rate limits
  */

/** Authenticated client information extracted from API key.
  */
case class AuthenticatedClient(
    apiKeyId: String,
    clientId: String,
    clientName: String,
    tier: ClientTier,
    permissions: Set[Permission],
)

/** Client tier determines rate limit profile.
  */
sealed trait ClientTier:
  def maxRequestsPerSecond: Int
  def maxBurstSize: Int

object ClientTier:
  case object Free extends ClientTier:
    val maxRequestsPerSecond = 10
    val maxBurstSize = 20

  case object Basic extends ClientTier:
    val maxRequestsPerSecond = 100
    val maxBurstSize = 200

  case object Premium extends ClientTier:
    val maxRequestsPerSecond = 1000
    val maxBurstSize = 2000

  case object Enterprise extends ClientTier:
    val maxRequestsPerSecond = 10000
    val maxBurstSize = 20000

  def fromString(s: String): Option[ClientTier] = s.toLowerCase match
    case "free" => Some(Free)
    case "basic" => Some(Basic)
    case "premium" => Some(Premium)
    case "enterprise" => Some(Enterprise)
    case _ => None

/** Permissions for API key holders.
  */
sealed trait Permission
object Permission:
  case object RateLimitCheck extends Permission
  case object RateLimitStatus extends Permission
  case object IdempotencyCheck extends Permission
  case object AdminMetrics extends Permission
  case object AdminConfig extends Permission

  val standard: Set[Permission] =
    Set(RateLimitCheck, RateLimitStatus, IdempotencyCheck)

  val admin: Set[Permission] = standard ++ Set(AdminMetrics, AdminConfig)

/** API key store trait for retrieving and validating keys.
  */
trait ApiKeyStore[F[_]]:
  /** Look up a client by API key */
  def findByKey(apiKey: String): F[Option[AuthenticatedClient]]

  /** Validate an API key is active */
  def isKeyValid(apiKey: String): F[Boolean]

object ApiKeyStore:
  /** In-memory API key store for development/testing.
    */
  def inMemory[F[_]: Sync](
      keys: Map[String, AuthenticatedClient],
  ): ApiKeyStore[F] = new ApiKeyStore[F]:
    override def findByKey(apiKey: String): F[Option[AuthenticatedClient]] =
      Sync[F].pure(keys.get(apiKey))

    override def isKeyValid(apiKey: String): F[Boolean] = Sync[F]
      .pure(keys.contains(apiKey))

  /** Default test keys for local development.
    */
  val testKeys: Map[String, AuthenticatedClient] = Map(
    "test-api-key" -> AuthenticatedClient(
      apiKeyId = "key_test_001",
      clientId = "client_test_001",
      clientName = "Test Client",
      tier = ClientTier.Premium,
      permissions = Permission.standard,
    ),
    "admin-api-key" -> AuthenticatedClient(
      apiKeyId = "key_admin_001",
      clientId = "client_admin_001",
      clientName = "Admin Client",
      tier = ClientTier.Enterprise,
      permissions = Permission.admin,
    ),
    "free-api-key" -> AuthenticatedClient(
      apiKeyId = "key_free_001",
      clientId = "client_free_001",
      clientName = "Free Tier Client",
      tier = ClientTier.Free,
      permissions = Permission.standard,
    ),
  )

/** Authentication error types.
  */
sealed trait AuthError extends RuntimeException
object AuthError:
  case object MissingApiKey extends AuthError:
    override def getMessage: String = "Missing API key in Authorization header"

  case object InvalidApiKey extends AuthError:
    override def getMessage: String = "Invalid API key"

  case class RateLimited(retryAfter: Int) extends AuthError:
    override def getMessage: String =
      s"Rate limited. Retry after $retryAfter seconds"

  case class InsufficientPermissions(required: Permission) extends AuthError:
    override def getMessage: String =
      s"Insufficient permissions: $required required"

/** API key authentication middleware.
  */
object ApiKeyAuth:

  /** Create authentication middleware.
    */
  def middleware[F[_]: Temporal: Logger](
      apiKeyStore: ApiKeyStore[F],
      authRateLimiter: Option[AuthRateLimiter[F]] = None,
  ): AuthMiddleware[F, AuthenticatedClient] =
    val logger = Logger[F]

    val authUser
        : Kleisli[[X] =>> OptionT[F, X], Request[F], AuthenticatedClient] =
      Kleisli { request =>
        OptionT {
          extractApiKey(request).flatMap {
            case None => logger.debug("Request missing API key") *>
                Temporal[F].pure(None)

            case Some(apiKey) =>
              // Look up the client
              apiKeyStore.findByKey(apiKey).flatMap {
                case None => logger
                    .warn(s"Invalid API key attempted: ${maskKey(apiKey)}") *>
                    Temporal[F].pure(None)

                case Some(client) =>
                  // Check rate limit on the rate limiter itself
                  authRateLimiter match
                    case Some(limiter) => limiter.checkLimit(client.apiKeyId)
                        .flatMap {
                          case true => logger
                              .debug(s"Authenticated client: ${client
                                  .clientName}") *> Temporal[F].pure(Some(client))
                          case false => logger.warn(s"Client ${client
                                .clientName} hit auth rate limit") *>
                              Temporal[F].pure(None)
                        }
                    case None => logger.debug(s"Authenticated client: ${client
                          .clientName}") *> Temporal[F].pure(Some(client))
              }
          }
        }
      }

    AuthMiddleware(authUser)

  /** Extract API key from Authorization header. Supports "Bearer <key>" and
    * "ApiKey <key>" formats.
    */
  private def extractApiKey[F[_]: Temporal](
      request: Request[F],
  ): F[Option[String]] = Temporal[F].pure(
    request.headers.get[Authorization].flatMap(auth =>
      auth.credentials match
        case Credentials.Token(scheme, token)
            if scheme.toString.equalsIgnoreCase("Bearer") ||
              scheme.toString.equalsIgnoreCase("ApiKey") => Some(token)
        case _ => None,
    ).orElse(
      // Also check X-Api-Key header
      request.headers.get(ci"X-Api-Key").map(_.head.value),
    ),
  )

  /** Mask API key for logging (show first/last 4 chars) */
  private def maskKey(key: String): String =
    if key.length > 8 then s"${key.take(4)}...${key.takeRight(4)}" else "****"

  /** Permission-checking middleware wrapper.
    */
  def requirePermission[F[_]: Temporal](
      permission: Permission,
  ): Kleisli[[X] =>> OptionT[F, X], AuthenticatedClient, AuthenticatedClient] =
    Kleisli(client =>
      OptionT(
        if client.permissions.contains(permission) then
          Temporal[F].pure(Some(client))
        else Temporal[F].pure(None),
      ),
    )

/** Rate limiter for authentication attempts. Protects against brute-force API
  * key guessing.
  */
trait AuthRateLimiter[F[_]]:
  /** Check if a client can make a request */
  def checkLimit(clientId: String): F[Boolean]

object AuthRateLimiter:
  import java.util.concurrent.atomic.AtomicInteger

  import com.github.benmanes.caffeine.cache.Caffeine

  /** Simple in-memory rate limiter for auth attempts.
    */
  def inMemory[F[_]: Temporal: Sync](
      maxRequestsPerMinute: Int = 100,
      maxFailedAttemptsPerMinute: Int = 10,
  ): F[AuthRateLimiter[F]] = Sync[F].delay {
    // Cache for tracking request counts per client
    val requestCounts = Caffeine.newBuilder()
      .expireAfterWrite(java.time.Duration.ofMinutes(1))
      .build[String, AtomicInteger]()

    new AuthRateLimiter[F]:
      override def checkLimit(clientId: String): F[Boolean] = Sync[F].delay {
        val counter = requestCounts.get(clientId, _ => new AtomicInteger(0))
        val count = counter.incrementAndGet()
        count <= maxRequestsPerMinute
      }
  }

/** Request context enriched with authentication info.
  */
case class AuthenticatedRequest[F[_]](
    client: AuthenticatedClient,
    request: Request[F],
    receivedAt: Long,
)
