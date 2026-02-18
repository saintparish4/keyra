package storage

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.Inspectors.*

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

    // — Token-bucket edge case tests

    "should always reject when cost exceeds bucket capacity" in {
      // cost > capacity means the bucket can never hold enough tokens, even when full
      val capacityProfile =
        RateLimitProfile(capacity = 5, refillRatePerSecond = 1.0, ttlSeconds = 3600)
      val test =
        for
          store <- InMemoryRateLimitStore.create[IO]
          decision <- store.checkAndConsume("edge-key", cost = 10, capacityProfile)
        yield decision

      test.asserting(decision => decision.shouldBe(a[RateLimitDecision.Rejected]))
    }

    "should always reject when cost exceeds capacity even on a fresh bucket" in {
      // Regression guard: a brand-new key is initialized with full capacity;
      // a cost exceeding capacity must still be rejected immediately.
      val capacityProfile =
        RateLimitProfile(capacity = 3, refillRatePerSecond = 10.0, ttlSeconds = 3600)
      val test =
        for
          store <- InMemoryRateLimitStore.create[IO]
          d1    <- store.checkAndConsume("fresh-key", cost = 4, capacityProfile)
          d2    <- store.checkAndConsume("fresh-key", cost = 4, capacityProfile)
        yield (d1, d2)

      test.asserting { case (d1, d2) =>
        d1.shouldBe(a[RateLimitDecision.Rejected])
        d2.shouldBe(a[RateLimitDecision.Rejected])
      }
    }

    "tokens remaining should never be reported as negative" in {
      // Exhaust the bucket fully, then verify all subsequent rejections carry
      // retryAfterSeconds > 0 (not a negative token count leaking out).
      val test =
        for
          store <- InMemoryRateLimitStore.create[IO]
          _     <- (1 to 10).toList.traverse(_ => store.checkAndConsume("neg-key", cost = 1, testProfile))
          decisions <- (1 to 5).toList.traverse(_ => store.checkAndConsume("neg-key", cost = 1, testProfile))
        yield decisions

      test.asserting { decisions =>
        forEvery(decisions) { d =>
          d.shouldBe(a[RateLimitDecision.Rejected])
          d.asInstanceOf[RateLimitDecision.Rejected].retryAfterSeconds should be >= 1
        }
      }
    }

    // — Rate-limit concurrency guarantee test

    "allowed count under concurrent load must not exceed capacity (plus refill epsilon)" in {
      // Fire 100 concurrent requests against a bucket with capacity 10 and
      // refillRate 1 token/s.  In the sub-millisecond window of a unit test
      // essentially zero tokens are refilled, so at most capacity + epsilon
      // requests should be allowed.
      //
      // Epsilon accounts for the small amount of wall-clock time that elapses
      // between bucket creation and the first checkAndConsume calls.  With a
      // refillRate of 1 token/s and a test window well under 1 s the refill is
      // < 1 token, so epsilon = 1 is a safe upper bound.
      val epsilon = 1
      val profile =
        RateLimitProfile(capacity = 10, refillRatePerSecond = 1.0, ttlSeconds = 3600)

      val test =
        for
          store <- InMemoryRateLimitStore.create[IO]
          decisions <- (1 to 100).toList.parTraverse(_ =>
            store.checkAndConsume("guarantee-key", cost = 1, profile),
          )
          allowedCount = decisions.count(_.allowed)
        yield allowedCount

      test.asserting { allowed =>
        allowed should be <= (profile.capacity + epsilon)
        allowed should be >= 1 // at least the first request is always allowed
      }
    }
  }
