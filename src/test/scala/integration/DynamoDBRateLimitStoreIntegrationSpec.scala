package integration

import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import core.{RateLimitDecision, RateLimitProfile}
import storage.DynamoDBRateLimitStore
import _root_.metrics.MetricsPublisher
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

/** Integration tests for DynamoDBRateLimitStore.
  *
  * These tests run against a real DynamoDB instance in LocalStack to verify
  * correct behavior including atomic operations.
  * Requires Docker. Without Docker, run unit tests only: sbt unitTest
  */
@Integration
class DynamoDBRateLimitStoreIntegrationSpec
    extends AsyncFreeSpec
    with AsyncIOSpec
    with Matchers
    with LocalStackIntegrationSpec
    with BeforeAndAfterEach {

  // Create logger for tests
  implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  // Test profile - use minimal refill rate to avoid refill during test execution
  val testProfile: RateLimitProfile =
    RateLimitProfile(capacity = 10, refillRatePerSecond = 0.00001, ttlSeconds = 3600)

  // Create store instance
  lazy val store: DynamoDBRateLimitStore[IO] = new DynamoDBRateLimitStore[IO](
    dynamoDbClient,
    testDynamoDBConfig.rateLimitTable,
    logger,
    MetricsPublisher.noop[IO],
  )

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    // Clear the table before each test for isolation
    clearTable(testDynamoDBConfig.rateLimitTable)
  }

  "DynamoDBRateLimitStore" - {

    "should allow requests within capacity" in
      store.checkAndConsume("integration-test-key-1", cost = 1, testProfile)
        .asserting { decision =>
          decision shouldBe a[RateLimitDecision.Allowed]
          decision.asInstanceOf[RateLimitDecision.Allowed]
            .tokensRemaining shouldBe 9
        }

    "should persist state across requests" in {
      val test = for {
        // First request
        d1 <- store.checkAndConsume("persist-key", cost = 3, testProfile)
        // Second request should see reduced tokens
        d2 <- store.checkAndConsume("persist-key", cost = 2, testProfile)
        // Third request
        d3 <- store.checkAndConsume("persist-key", cost = 1, testProfile)
      } yield (d1, d2, d3)

      test.asserting { case (d1, d2, d3) =>
        d1.asInstanceOf[RateLimitDecision.Allowed].tokensRemaining shouldBe 7 // 10 - 3
        d2.asInstanceOf[RateLimitDecision.Allowed].tokensRemaining shouldBe 5 // 7 - 2
        d3.asInstanceOf[RateLimitDecision.Allowed].tokensRemaining shouldBe 4 // 5 - 1
      }
    }

    "should reject when capacity exhausted" in {
      val test = for {
        // Exhaust all tokens
        _ <- store.checkAndConsume("exhaust-key", cost = 10, testProfile)
        // This should be rejected
        decision <- store.checkAndConsume("exhaust-key", cost = 1, testProfile)
      } yield decision

      test.asserting { decision =>
        decision shouldBe a[RateLimitDecision.Rejected]
        val rejected = decision.asInstanceOf[RateLimitDecision.Rejected]
        rejected.retryAfterSeconds should be >= 1
      }
    }

    "should handle concurrent requests atomically" in {
      val concurrentRequests = 20
      // Use minimal refill rate to ensure deterministic results in concurrent scenario
      // (essentially no refill during test execution, but satisfies positive rate requirement)
      val minimalRefillProfile = testProfile.copy(refillRatePerSecond = 0.00001)

      val test = for {
        // Fire concurrent requests
        decisions <- (1 to concurrentRequests).toList.parTraverse(_ =>
          store.checkAndConsume("concurrent-key", cost = 1, minimalRefillProfile),
        )

        allowed = decisions.count(_.allowed)
        rejected = decisions.count(!_.allowed)
      } yield (allowed, rejected)

      test.asserting { case (allowed, rejected) =>
        // With capacity 10 and minimal refill, exactly 10 should be allowed
        allowed shouldBe 10
        rejected shouldBe 10
      }
    }

    "should handle concurrent requests with retries" in {
      // Test that OCC retries work correctly under contention
      val concurrentRequests = 15
      // Use minimal refill rate to ensure deterministic results
      val smallProfile = testProfile
        .copy(capacity = 5, refillRatePerSecond = 0.00001)

      val test = for {
        decisions <- (1 to concurrentRequests).toList.parTraverse(_ =>
          store.checkAndConsume("retry-key", cost = 1, smallProfile),
        )

        allowed = decisions.count(_.allowed)
      } yield allowed

      test.asserting(allowed =>
        // With capacity 5 and minimal refill, exactly 5 should be allowed
        allowed shouldBe 5,
      )
    }

    "should isolate different keys" in {
      val test = for {
        // Exhaust key-1
        _ <- store.checkAndConsume("isolated-key-1", cost = 10, testProfile)
        rejected <- store
          .checkAndConsume("isolated-key-1", cost = 1, testProfile)

        // key-2 should still have full capacity
        allowed <- store.checkAndConsume("isolated-key-2", cost = 1, testProfile)
      } yield (rejected, allowed)

      test.asserting { case (rejected, allowed) =>
        rejected.allowed shouldBe false
        allowed.allowed shouldBe true
        allowed.asInstanceOf[RateLimitDecision.Allowed].tokensRemaining shouldBe
          9
      }
    }

    "should return correct status" in {
      val test = for {
        // Consume some tokens
        _ <- store.checkAndConsume("status-key", cost = 4, testProfile)

        // Check status
        status <- store.getStatus("status-key", testProfile)
      } yield status

      test.asserting { status =>
        status shouldBe defined
        status.get.tokensRemaining shouldBe 6 // 10 - 4
      }
    }

    "should return None for non-existent key status" in
      store.getStatus("non-existent-key", testProfile)
        .asserting(status => status shouldBe None)

    "should pass health check" in
      store.healthCheck.asserting(healthy => healthy shouldBe Right(()))

    "should handle high cost requests" in {
      val highCostProfile = testProfile.copy(capacity = 100)

      val test = for {
        // Request with cost = 50
        d1 <- store.checkAndConsume("high-cost-key", cost = 50, highCostProfile)
        // Another request with cost = 50
        d2 <- store.checkAndConsume("high-cost-key", cost = 50, highCostProfile)
        // This should be rejected (only ~0 tokens left)
        d3 <- store.checkAndConsume("high-cost-key", cost = 1, highCostProfile)
      } yield (d1, d2, d3)

      test.asserting { case (d1, d2, d3) =>
        d1.allowed shouldBe true
        d2.allowed shouldBe true
        d3.allowed shouldBe false
      }
    }
  }
}
