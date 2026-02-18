package storage

import java.lang.reflect.{InvocationHandler, Proxy}
import java.util.concurrent.CompletableFuture

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*
import org.typelevel.ci.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import config.*
import core.*
import events.EventPublisher
import _root_.metrics.MetricsPublisher
import resilience.*
import security.{AuthenticatedClient, ClientTier, Permission}
import api.IdempotencyApi

import cats.effect.*
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import io.circe.parser.*
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*

/** Tests for error handling in DynamoDB-backed stores and the resilient wrapper.
  *
  * Tests are grouped into three areas:
  *
  *   1. `ResilientRateLimitStore` degrades gracefully when the underlying store
  *      raises DynamoDB-style exceptions (fails open in AllowAll mode, logs the
  *      error, increments the `RateLimitDegraded` metric).
  *
  *   2. `DynamoDBRateLimitStore` with a stubbed DynamoDB client that returns
  *      corrupt data → fails open on the corrupt key (grants full capacity),
  *      logs an ERROR, and increments the `CorruptStateRead` metric.
  *
  *   3. `IdempotencyApi` maps `CorruptIdempotencyRecordException` to a 503
  *      with a structured `storage_corruption` error body.
  */
class DynamoDBStoreErrorSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers:

  // ── helpers ────────────────────────────────────────────────────────────────

  given Logger[IO] = NoOpLogger[IO]

  private val testProfile =
    RateLimitProfile(capacity = 10, refillRatePerSecond = 1.0, ttlSeconds = 3600)

  private val testClient = AuthenticatedClient(
    apiKeyId = "test-client",
    clientId = "test-client",
    clientName = "Test",
    tier = ClientTier.Free,
    permissions = Permission.standard,
  )

  /** ResilienceConfig tuned for fast unit tests: no retries, no circuit breaker,
    * no bulkhead, generous timeout so the failure is never a timeout.
    */
  private val fastResilienceConfig: ResilienceConfig = ResilienceConfig(
    circuitBreaker = CircuitBreakerSettings(
      enabled = false,
      dynamodb = config.CircuitBreakerConfig(),
      kinesis = config.CircuitBreakerConfig(),
    ),
    retry = RetrySettings(
      dynamodb = RetryConfig(
        maxRetries = 0,
        baseDelay = 1.millis,
        maxDelay = 1.millis,
        multiplier = 1.0,
      ),
      kinesis = RetryConfig(),
    ),
    bulkhead = BulkheadSettings(enabled = false),
    timeout = TimeoutSettings(rateLimitCheck = 5.seconds),
  )

  /** A MetricsPublisher that records increment calls so tests can assert on them. */
  private def capturingMetrics(
      ref: Ref[IO, List[String]],
  ): MetricsPublisher[IO] = new MetricsPublisher[IO]:
    def increment(name: String, dims: Map[String, String] = Map.empty): IO[Unit] =
      ref.update(_ :+ name)
    def gauge(name: String, value: Double, dims: Map[String, String] = Map.empty): IO[Unit] = IO.unit
    def recordLatency(name: String, latencyMs: Double, dims: Map[String, String] = Map.empty): IO[Unit] = IO.unit
    def recordRateLimitDecision(allowed: Boolean, clientId: String, tier: String = "unknown"): IO[Unit] = IO.unit
    def recordCircuitBreakerState(name: String, state: String, failures: Int): IO[Unit] = IO.unit
    def recordCacheMetrics(cacheName: String, hitRate: Double, size: Long): IO[Unit] = IO.unit
    def recordDegradedOperation(operation: String): IO[Unit] = IO.unit
    def timed[A](name: String, dims: Map[String, String] = Map.empty)(fa: IO[A]): IO[A] = fa
    def flush: IO[Unit] = IO.unit

  /** A Logger that captures error messages for assertion. */
  private def capturingLogger(
      ref: Ref[IO, List[String]],
  ): Logger[IO] = new Logger[IO]:
    def error(t: Throwable)(msg: => String): IO[Unit] = ref.update(_ :+ msg)
    def error(msg: => String): IO[Unit] = ref.update(_ :+ msg)
    def warn(t: Throwable)(msg: => String): IO[Unit] = IO.unit
    def warn(msg: => String): IO[Unit] = IO.unit
    def info(t: Throwable)(msg: => String): IO[Unit] = IO.unit
    def info(msg: => String): IO[Unit] = IO.unit
    def debug(t: Throwable)(msg: => String): IO[Unit] = IO.unit
    def debug(msg: => String): IO[Unit] = IO.unit
    def trace(t: Throwable)(msg: => String): IO[Unit] = IO.unit
    def trace(msg: => String): IO[Unit] = IO.unit

  /** Build a DynamoDbAsyncClient proxy that returns a fixed GetItemResponse.
    * putItem and updateItem return empty success responses. All other calls
    * fail immediately — if a test inadvertently calls them the test will fail.
    */
  private def stubClient(getItemResp: GetItemResponse): DynamoDbAsyncClient =
    Proxy
      .newProxyInstance(
        classOf[DynamoDbAsyncClient].getClassLoader,
        Array(classOf[DynamoDbAsyncClient]),
        new InvocationHandler:
          override def invoke(
              proxy: Object,
              method: java.lang.reflect.Method,
              args: Array[Object],
          ): Object = method.getName match
            case "getItem" =>
              CompletableFuture.completedFuture(getItemResp)
            case "putItem" =>
              CompletableFuture.completedFuture(PutItemResponse.builder().build())
            case "updateItem" =>
              CompletableFuture.completedFuture(UpdateItemResponse.builder().build())
            case "describeTable" =>
              CompletableFuture.completedFuture(
                DescribeTableResponse.builder().build(),
              )
            case "serviceName" => "DynamoDB"
            case "close"       => null // void
            case other =>
              CompletableFuture.failedFuture[Object](
                new UnsupportedOperationException(s"Stub does not implement: $other"),
              ),
      )
      .asInstanceOf[DynamoDbAsyncClient]

  /** A GetItemResponse that contains an existing rate-limit item with a
    * non-numeric "tokens" attribute value — this triggers the corrupt-state
    * path in parseState.
    *
    * NOTE: "NaN".toDouble succeeds in Scala/Java (returns Double.NaN).  We must
    * use a string that actually throws NumberFormatException so that parseState
    * returns Left and the store falls back to full-capacity (fails open).
    */
  private def corruptTokensResponse(key: String): GetItemResponse =
    val item = Map(
      "pk"           -> AttributeValue.builder().s(s"ratelimit#$key").build(),
      "tokens"       -> AttributeValue.builder().n("not_a_number").build(), // throws NFE
      "lastRefillMs" -> AttributeValue.builder().n("0").build(),
      "version"      -> AttributeValue.builder().n("1").build(),
    ).asJava
    GetItemResponse.builder().item(item).build()

  /** A rate-limit store that always raises the provided exception. */
  private def failingStore(ex: Throwable): RateLimitStore[IO] =
    new RateLimitStore[IO]:
      def checkAndConsume(key: String, cost: Int, profile: RateLimitProfile): IO[RateLimitDecision] =
        IO.raiseError(ex)
      def getStatus(key: String, profile: RateLimitProfile): IO[Option[RateLimitDecision.Allowed]] =
        IO.raiseError(ex)
      def healthCheck: IO[Either[String, Unit]] =
        IO.pure(Left(ex.getMessage))

  // ── 5.4a: ResilientRateLimitStore degradation ──────────────────────────────

  "ResilientRateLimitStore — AllowAll degradation mode" - {

    "fails open (returns Allowed) when the underlying store raises an exception" in {
      val ex = new RuntimeException("simulated DynamoDB failure")

      ResilientRateLimitStore(
        failingStore(ex),
        fastResilienceConfig,
        MetricsPublisher.noop[IO],
        EventPublisher.noop[IO],
        GracefulDegradation.DegradationMode.AllowAll,
      ).use { resilientStore =>
        resilientStore
          .checkAndConsume("test-key", cost = 1, testProfile)
          .asserting { decision =>
            decision shouldBe a[RateLimitDecision.Allowed]
            // GracefulDegradation.AllowAll grants 100 tokens as a sentinel value
            decision.asInstanceOf[RateLimitDecision.Allowed].tokensRemaining shouldBe 100
          }
      }
    }

    "fails open when the underlying store raises ProvisionedThroughputExceededException" in {
      val ex = software.amazon.awssdk.services.dynamodb.model
        .ProvisionedThroughputExceededException
        .builder()
        .message("Throughput exceeded")
        .build()

      ResilientRateLimitStore(
        failingStore(ex),
        fastResilienceConfig,
        MetricsPublisher.noop[IO],
        EventPublisher.noop[IO],
        GracefulDegradation.DegradationMode.AllowAll,
      ).use { resilientStore =>
        resilientStore
          .checkAndConsume("throttled-key", cost = 1, testProfile)
          .asserting { decision =>
            decision shouldBe a[RateLimitDecision.Allowed]
          }
      }
    }

    "increments RateLimitDegraded metric on any underlying failure" in {
      val ex = new java.io.IOException("simulated network timeout")

      (for
        metricNames <- Ref.of[IO, List[String]](Nil)
        metrics = capturingMetrics(metricNames)

        _ <- ResilientRateLimitStore(
          failingStore(ex),
          fastResilienceConfig,
          metrics,
          EventPublisher.noop[IO],
          GracefulDegradation.DegradationMode.AllowAll,
        ).use { resilientStore =>
          resilientStore.checkAndConsume("metric-key", cost = 1, testProfile)
        }

        recorded <- metricNames.get
      yield recorded).asserting { metrics =>
        metrics should contain("RateLimitDegraded")
      }
    }

    "rejects all requests in RejectAll degradation mode" in {
      val ex = new RuntimeException("store down")

      ResilientRateLimitStore(
        failingStore(ex),
        fastResilienceConfig,
        MetricsPublisher.noop[IO],
        EventPublisher.noop[IO],
        GracefulDegradation.DegradationMode.RejectAll,
      ).use { resilientStore =>
        resilientStore
          .checkAndConsume("reject-key", cost = 1, testProfile)
          .asserting { decision =>
            decision shouldBe a[RateLimitDecision.Rejected]
          }
      }
    }
  }

  // ──  DynamoDBRateLimitStore corrupt-state path ────────────────────────

  "DynamoDBRateLimitStore — corrupt stored state" - {

    "fails open (grants full capacity) when stored tokens attribute is malformed" in {
      // The stub returns a GetItemResponse with tokens = "NaN" which cannot be
      // parsed as a Double.  DynamoDBRateLimitStore.getOrInitState detects the
      // Left parse result, logs an ERROR, increments CorruptStateRead, and
      // falls back to a fresh full-capacity bucket — i.e. allows the request.
      val key    = "corrupt-state-key"
      val client = stubClient(corruptTokensResponse(key))

      (for
        errorLogs   <- Ref.of[IO, List[String]](Nil)
        metricNames <- Ref.of[IO, List[String]](Nil)
        metrics     = capturingMetrics(metricNames)
        logger      = capturingLogger(errorLogs)

        store    = DynamoDBRateLimitStore[IO](client, "test-table", logger, metrics)
        decision <- store.checkAndConsume(key, cost = 1, testProfile)

        logs    <- errorLogs.get
        metrics <- metricNames.get
      yield (decision, logs, metrics)).asserting { case (decision, logs, metrics) =>
        // 1. Fails open → request is allowed
        decision shouldBe a[RateLimitDecision.Allowed]

        // 2. An ERROR is logged mentioning the key and the corrupt-state reason
        logs.exists(msg => msg.contains(key) && msg.contains("Corrupt")) shouldBe true

        // 3. CorruptStateRead metric is incremented
        metrics should contain("CorruptStateRead")
      }
    }
  }

  // ── IdempotencyApi 503 on corrupt record ─────────────────────────────

  "IdempotencyApi — corrupt idempotency record" - {

    "returns 503 with storage_corruption error when store raises CorruptIdempotencyRecordException" in {
      val corruptKey = "corrupt-idempotency-key"
      val detail     = "unknown status value: 'INVALID_STATUS'"

      val corruptStore: IdempotencyStore[IO] = new IdempotencyStore[IO]:
        def check(key: String, clientId: String, ttlSeconds: Long): IO[IdempotencyResult] =
          IO.raiseError(new CorruptIdempotencyRecordException(key, detail))
        def storeResponse(key: String, response: StoredResponse): IO[Boolean] = IO.pure(false)
        def markFailed(key: String): IO[Boolean] = IO.pure(false)
        def get(key: String): IO[Option[IdempotencyRecord]] = IO.pure(None)
        def healthCheck: IO[Either[String, Unit]] = IO.pure(Right(()))

      val idempotencyConfig = IdempotencyConfig(
        defaultTtlSeconds = 3600L,
        maxTtlSeconds = 3600L,
      )

      val api = IdempotencyApi[IO](
        corruptStore,
        idempotencyConfig,
        EventPublisher.noop[IO],
        MetricsPublisher.noop[IO],
        summon[Logger[IO]],
      )

      val body = s"""{"idempotencyKey": "$corruptKey"}"""
      val req = Request[IO](Method.POST, uri"/v1/idempotency/check")
        .withEntity(body)
        .putHeaders(
          headers.`Content-Type`(MediaType.application.json),
          headers.Authorization(Credentials.Token(ci"Bearer", "test-key")),
        )

      (for
        response <- api.check(req, testClient)
        bodyText <- response.body.compile.toVector.map(_.map(_.toChar).mkString)
      yield (response.status, parse(bodyText).toOption)
      ).asserting { case (status, maybeJson) =>
        status shouldBe Status.ServiceUnavailable
        val cursor = maybeJson.get.hcursor
        cursor.downField("error").as[String].toOption shouldBe Some("storage_corruption")
        cursor.downField("message").as[String].toOption
          .exists(_.contains(detail)) shouldBe true
      }
    }
  }
