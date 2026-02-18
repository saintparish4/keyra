package storage

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import core.{RateLimitDecision, RateLimitProfile}
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

class InMemoryRateLimitStoreSpec
    extends AsyncFreeSpec with AsyncIOSpec with Matchers:

  val testProfile: RateLimitProfile =
    RateLimitProfile(capacity = 10, refillRatePerSecond = 1.0, ttlSeconds = 3600)

  "InMemoryRateLimitStore" - {

    "should allow requests within capacity" in {
      val test =
        for
          store <- InMemoryRateLimitStore.create[IO]
          decision <- store.checkAndConsume("test-key", cost = 1, testProfile)
        yield decision

      test.asserting { (decision: RateLimitDecision) =>
        decision.shouldBe(a[RateLimitDecision.Allowed])
        decision.asInstanceOf[RateLimitDecision.Allowed].tokensRemaining
          .shouldBe(9)
      }
    }

    "should reject requests over capacity" in {
      val test =
        for
          store <- InMemoryRateLimitStore.create[IO]
          // Consume all tokens
          _ <- (1 to 10).toList.traverse(_ =>
            store.checkAndConsume("test-key", cost = 1, testProfile),
          )
          // This should be rejected
          decision <- store.checkAndConsume("test-key", cost = 1, testProfile)
        yield decision

      test.asserting((decision: RateLimitDecision) =>
        decision.shouldBe(a[RateLimitDecision.Rejected]),
      )
    }

    "should handle cost > 1" in {
      val test =
        for
          store <- InMemoryRateLimitStore.create[IO]
          decision <- store.checkAndConsume("test-key", cost = 5, testProfile)
        yield decision

      test.asserting { (decision: RateLimitDecision) =>
        decision.shouldBe(a[RateLimitDecision.Allowed])
        decision.asInstanceOf[RateLimitDecision.Allowed].tokensRemaining
          .shouldBe(5)
      }
    }

    "should reject when cost exceeds available tokens" in {
      val test =
        for
          store <- InMemoryRateLimitStore.create[IO]
          _ <- store.checkAndConsume("test-key", cost = 8, testProfile)
          // Only 2 tokens left, requesting 5
          decision <- store.checkAndConsume("test-key", cost = 5, testProfile)
        yield decision

      test.asserting((decision: RateLimitDecision) =>
        decision.shouldBe(a[RateLimitDecision.Rejected]),
      )
    }

    "should track different keys independently" in {
      val test =
        for
          store <- InMemoryRateLimitStore.create[IO]
          _ <- store.checkAndConsume("key-1", cost = 10, testProfile)
          // key-1 is exhausted, but key-2 should be full
          decision <- store.checkAndConsume("key-2", cost = 1, testProfile)
        yield decision

      test.asserting { (decision: RateLimitDecision) =>
        decision.shouldBe(a[RateLimitDecision.Allowed])
        decision.asInstanceOf[RateLimitDecision.Allowed].tokensRemaining
          .shouldBe(9)
      }
    }

    "should return correct status for existing key" in {
      val test =
        for
          store <- InMemoryRateLimitStore.create[IO]
          _ <- store.checkAndConsume("test-key", cost = 3, testProfile)
          status <- store.getStatus("test-key", testProfile)
        yield status

      test.asserting { status =>
        status.shouldBe(defined)
        status.get shouldBe a[RateLimitDecision.Allowed]
        status.get.tokensRemaining shouldBe 7
      }
    }

    "should return None for non-existent key" in {
      val test =
        for
          store <- InMemoryRateLimitStore.create[IO]
          status <- store.getStatus("non-existent", testProfile)
        yield status

      test.asserting(status => status.shouldBe(None))
    }

    "should be thread-safe under concurrent access" in {
      val test =
        for
          store <- InMemoryRateLimitStore.create[IO]
          // Fire 20 concurrent requests for a bucket with capacity 10
          decisions <- (1 to 20).toList.parTraverse(_ =>
            store.checkAndConsume("concurrent-key", cost = 1, testProfile),
          )

          allowedCount = decisions.count((d: RateLimitDecision) => d.allowed)
          rejectedCount = decisions.count((d: RateLimitDecision) => !d.allowed)
        yield (allowedCount, rejectedCount)

      test.asserting { case (allowed, rejected) =>
        // Exactly 10 should be allowed (capacity), 10 should be rejected
        allowed.shouldBe(10)
        rejected.shouldBe(10)
      }
    }

    "health check should always return true" in {
      val test =
        for
          store <- InMemoryRateLimitStore.create[IO]
          healthy <- store.healthCheck
        yield healthy

      test.asserting(healthy => healthy.shouldBe(Right(())))
    }
  }
