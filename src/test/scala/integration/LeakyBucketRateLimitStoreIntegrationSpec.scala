package integration

import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import core.{RateLimitDecision, RateLimitProfile}
import storage.LeakyBucketRateLimitStore
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

/** Integration tests for LeakyBucketRateLimitStore.
  *
  * These tests run against a real DynamoDB instance in LocalStack to verify
  * leaky bucket behavior: level-based state, allow when level + cost <=
  * capacity, reject with retryAfter when over capacity, and OCC under
  * concurrency. Requires Docker. Without Docker, run unit tests only: sbt
  * unitTest
  */
@Integration
class LeakyBucketRateLimitStoreIntegrationSpec
    extends AsyncFreeSpec
    with AsyncIOSpec
    with Matchers
    with LocalStackIntegrationSpec
    with BeforeAndAfterEach {

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  // Same schema as token bucket; minimal leak rate so behavior is deterministic in short tests
  val testProfile: RateLimitProfile = RateLimitProfile(
    capacity = 10,
    refillRatePerSecond = 0.00001,
    ttlSeconds = 3600,
  )

  lazy val store: LeakyBucketRateLimitStore[IO] = LeakyBucketRateLimitStore[IO](
    dynamoDbClient,
    testDynamoDBConfig.rateLimitTable,
  )

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    clearTable(testDynamoDBConfig.rateLimitTable)
  }

  "LeakyBucketRateLimitStore" - {

    "should allow requests within capacity" in
      store.checkAndConsume("leaky-test-key-1", cost = 1, testProfile).asserting {
        decision =>
          decision shouldBe a[RateLimitDecision.Allowed]
          decision.asInstanceOf[RateLimitDecision.Allowed]
            .tokensRemaining shouldBe 9
      }

    "should persist state across requests" in {
      val test = for {
        d1 <- store.checkAndConsume("leaky-persist-key", cost = 3, testProfile)
        d2 <- store.checkAndConsume("leaky-persist-key", cost = 2, testProfile)
        d3 <- store.checkAndConsume("leaky-persist-key", cost = 1, testProfile)
      } yield (d1, d2, d3)

      test.asserting { case (d1, d2, d3) =>
        d1.asInstanceOf[RateLimitDecision.Allowed].tokensRemaining shouldBe 7
        d2.asInstanceOf[RateLimitDecision.Allowed].tokensRemaining shouldBe 5
        d3.asInstanceOf[RateLimitDecision.Allowed].tokensRemaining shouldBe 4
      }
    }

    "should reject when capacity exhausted (no burst beyond capacity)" in {
      val test = for {
        _ <- store.checkAndConsume("leaky-exhaust-key", cost = 10, testProfile)
        decision <- store
          .checkAndConsume("leaky-exhaust-key", cost = 1, testProfile)
      } yield decision

      test.asserting { decision =>
        decision shouldBe a[RateLimitDecision.Rejected]
        val rejected = decision.asInstanceOf[RateLimitDecision.Rejected]
        rejected.retryAfterSeconds should be >= 1
      }
    }

    "should handle concurrent requests atomically" in {
      val minimalLeakProfile = testProfile.copy(refillRatePerSecond = 0.00001)
      val test = for {
        decisions <- (1 to 20).toList.parTraverse(_ =>
          store.checkAndConsume(
            "leaky-concurrent-key",
            cost = 1,
            minimalLeakProfile,
          ),
        )
        allowed = decisions.count(_.allowed)
        rejected = decisions.count(!_.allowed)
      } yield (allowed, rejected)

      test.asserting { case (allowed, rejected) =>
        allowed shouldBe 10
        rejected shouldBe 10
      }
    }

    "should isolate different keys" in {
      val test = for {
        _ <- store.checkAndConsume("leaky-isolated-1", cost = 10, testProfile)
        rejected <- store
          .checkAndConsume("leaky-isolated-1", cost = 1, testProfile)
        allowed <- store
          .checkAndConsume("leaky-isolated-2", cost = 1, testProfile)
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
        _ <- store.checkAndConsume("leaky-status-key", cost = 4, testProfile)
        status <- store.getStatus("leaky-status-key", testProfile)
      } yield status

      test.asserting { status =>
        status shouldBe defined
        status.get.tokensRemaining shouldBe 6
      }
    }

    "should return None for non-existent key status" in
      store.getStatus("leaky-non-existent-key", testProfile)
        .asserting(status => status shouldBe None)

    "should pass health check" in
      store.healthCheck.asserting(healthy => healthy shouldBe Right(()))

    "should handle high cost requests" in {
      val highCostProfile = testProfile.copy(capacity = 100)
      val test = for {
        d1 <- store
          .checkAndConsume("leaky-high-cost-key", cost = 50, highCostProfile)
        d2 <- store
          .checkAndConsume("leaky-high-cost-key", cost = 50, highCostProfile)
        d3 <- store
          .checkAndConsume("leaky-high-cost-key", cost = 1, highCostProfile)
      } yield (d1, d2, d3)

      test.asserting { case (d1, d2, d3) =>
        d1.allowed shouldBe true
        d2.allowed shouldBe true
        d3.allowed shouldBe false
      }
    }
  }
}
