package resilience

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.typelevel.log4cats.Logger

import com.github.benmanes.caffeine.cache.{Cache, Caffeine}

import cats.effect.*
import cats.syntax.all.*

/** Local cache configuration.
  *
  * @param maxSize
  *   Maximum number of entries
  * @param ttl
  *   Time-to-live for entries
  * @param recordStats
  *   Whether to record cache statistics
  */
case class LocalCacheConfig(
    maxSize: Long = 10000,
    ttl: FiniteDuration = 1.second,
    recordStats: Boolean = true,
)

object LocalCacheConfig:
  val default: LocalCacheConfig = LocalCacheConfig()

  /** Aggressive caching for high-throughput scenarios */
  val aggressive: LocalCacheConfig =
    LocalCacheConfig(maxSize = 50000, ttl = 5.seconds)

  /** Conservative caching for consistency-sensitive scenarios */
  val conservative: LocalCacheConfig =
    LocalCacheConfig(maxSize = 1000, ttl = 100.millis)

/** Cache statistics for monitoring.
  */
case class CacheStats(
    hitCount: Long,
    missCount: Long,
    loadCount: Long,
    evictionCount: Long,
    hitRate: Double,
    estimatedSize: Long,
)

/** Generic local cache wrapper around Caffeine. Provides a functional interface
  * for caching with proper resource management.
  *
  * @tparam F
  *   Effect type
  * @tparam K
  *   Key type
  * @tparam V
  *   Value type
  */
trait LocalCache[F[_], K, V]:
  /** Get a value from the cache */
  def get(key: K): F[Option[V]]

  /** Put a value in the cache */
  def put(key: K, value: V): F[Unit]

  /** Get or compute a value */
  def getOrCompute(key: K)(compute: F[V]): F[V]

  /** Invalidate a specific key */
  def invalidate(key: K): F[Unit]

  /** Invalidate all entries */
  def invalidateAll: F[Unit]

  /** Get cache statistics */
  def stats: F[CacheStats]

object LocalCache:

  /** Create a local cache as a Resource for proper lifecycle management.
    */
  def resource[F[_]: Sync, K <: AnyRef, V <: AnyRef](
      name: String,
      config: LocalCacheConfig = LocalCacheConfig.default,
  ): Resource[F, LocalCache[F, K, V]] = Resource
    .make(create[F, K, V](name, config))(_.invalidateAll)

  /** Create a local cache (without Resource wrapper). Caller is responsible for
    * cleanup.
    */
  def create[F[_]: Sync, K <: AnyRef, V <: AnyRef](
      name: String,
      config: LocalCacheConfig = LocalCacheConfig.default,
  ): F[LocalCache[F, K, V]] = Sync[F].delay {
    val builder = Caffeine.newBuilder().maximumSize(config.maxSize)
      .expireAfterWrite(java.time.Duration.ofMillis(config.ttl.toMillis))

    val cache: Cache[K, V] =
      if config.recordStats then builder.recordStats().build()
      else builder.build()

    new CaffeineCache[F, K, V](name, cache, config.recordStats)
  }

/** Caffeine-backed cache implementation.
  */
private class CaffeineCache[F[_]: Sync, K <: AnyRef, V <: AnyRef](
    name: String,
    underlying: Cache[K, V],
    recordStats: Boolean,
) extends LocalCache[F, K, V]:

  override def get(key: K): F[Option[V]] = Sync[F]
    .delay(Option(underlying.getIfPresent(key)))

  override def put(key: K, value: V): F[Unit] = Sync[F]
    .delay(underlying.put(key, value))

  override def getOrCompute(key: K)(compute: F[V]): F[V] = get(key).flatMap {
    case Some(value) => Sync[F].pure(value)
    case None => compute.flatMap(value => put(key, value).as(value))
  }

  override def invalidate(key: K): F[Unit] = Sync[F]
    .delay(underlying.invalidate(key))

  override def invalidateAll: F[Unit] = Sync[F].delay(underlying.invalidateAll())

  override def stats: F[CacheStats] = Sync[F].delay {
    if recordStats then
      val s = underlying.stats()
      CacheStats(
        hitCount = s.hitCount(),
        missCount = s.missCount(),
        loadCount = s.loadCount(),
        evictionCount = s.evictionCount(),
        hitRate = s.hitRate(),
        estimatedSize = underlying.estimatedSize(),
      )
    else
      CacheStats(
        hitCount = 0,
        missCount = 0,
        loadCount = 0,
        evictionCount = 0,
        hitRate = 0.0,
        estimatedSize = underlying.estimatedSize(),
      )
  }

/** Cached rate limit store that wraps another store with local caching.
  *
  * This provides:
  *   - Reduced DynamoDB reads for frequent checks
  *   - Protection against DynamoDB throttling
  *   - Lower latency for repeated requests
  *
  * Trade-off: Slight over-allowance possible due to cache staleness. Suitable
  * for scenarios where eventual consistency is acceptable.
  */
object CachedRateLimitStore:
  import core.{RateLimitDecision, RateLimitProfile, RateLimitStore}

  /** Cached state for rate limit buckets. Only caches the "status" read, not
    * the atomic consume operation.
    */
  case class CachedBucketState(
      tokens: Int,
      resetAt: java.time.Instant,
      cachedAt: Long,
  )

  /** Wrap a rate limit store with local caching for status reads.
    *
    * Note: The checkAndConsume operation is NOT cached - it always goes to the
    * underlying store for atomicity. Only getStatus is cached.
    */
  def wrap[F[_]: Async: Logger](
      underlying: RateLimitStore[F],
      cacheConfig: LocalCacheConfig = LocalCacheConfig.default,
  ): Resource[F, RateLimitStore[F]] = LocalCache
    .resource[F, String, CachedBucketState]("rate-limit-status", cacheConfig)
    .map { cache =>
      new RateLimitStore[F]:
        private val logger = Logger[F]

        override def checkAndConsume(
            key: String,
            cost: Int,
            profile: RateLimitProfile,
        ): F[RateLimitDecision] =
          // Always go to underlying store for atomic operations
          underlying.checkAndConsume(key, cost, profile).flatTap(decision =>
            // Update cache with result
            decision match
              case RateLimitDecision.Allowed(tokens, resetAt) => Clock[F]
                  .realTime.map(_.toMillis).flatMap(now =>
                    cache.put(key, CachedBucketState(tokens, resetAt, now)),
                  )
              case _ => Async[F].unit,
          )

        override def getStatus(
            key: String,
            profile: RateLimitProfile,
        ): F[Option[RateLimitDecision.Allowed]] = cache.get(key).flatMap {
          case Some(cached) => Clock[F].realTime.map(_.toMillis).flatMap(now =>
              // Check if cache is still valid
              if now - cached.cachedAt < cacheConfig.ttl.toMillis then
                logger.debug(s"Cache hit for key: $key") *> Async[F].pure(Some(
                  RateLimitDecision.Allowed(cached.tokens, cached.resetAt),
                ))
              else
                // Cache expired, fetch fresh
                fetchAndCache(key, profile),
            )
          case None => fetchAndCache(key, profile)
        }

        override def healthCheck: F[Either[String, Unit]] =
          underlying.healthCheck

        private def fetchAndCache(
            key: String,
            profile: RateLimitProfile,
        ): F[Option[RateLimitDecision.Allowed]] = underlying
          .getStatus(key, profile).flatTap {
            case Some(status) =>
              Clock[F].realTime.map(_.toMillis).flatMap(now =>
                cache.put(
                  key,
                  CachedBucketState(status.tokensRemaining, status.resetAt, now),
                ),
              )
            case None => Async[F].unit
          }
    }
