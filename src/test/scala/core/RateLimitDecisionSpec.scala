package core

import java.time.Instant

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec

class RateLimitDecisionSpec
    extends AsyncFreeSpec with AsyncIOSpec with Matchers:

  "RateLimitDecision" - {
    "Allowed should have allowed = true" in {
      val decision = RateLimitDecision.Allowed(95, Instant.now())
      decision.allowed shouldBe true
      decision.tokensRemaining shouldBe 95
    }

    "Rejected should have allowed = false" in {
      val decision = RateLimitDecision.Rejected(5, Instant.now())
      decision.allowed shouldBe false
      decision.retryAfterSeconds shouldBe 5
    }
  }

  "RateLimitProfile" - {
    "should validate positive capacity" in {
      an[IllegalArgumentException] should be thrownBy RateLimitProfile(
        capacity = 0,
        refillRatePerSecond = 10,
        ttlSeconds = 3600,
      )
    }

    "should validate positive refill rate" in {
      an[IllegalArgumentException] should be thrownBy RateLimitProfile(
        capacity = 100,
        refillRatePerSecond = 0,
        ttlSeconds = 3600,
      )
    }

    "should accept valid configuration" in {
      val profile = RateLimitProfile(
        capacity = 100,
        refillRatePerSecond = 10,
        ttlSeconds = 3600,
      )
      profile.capacity shouldBe 100
      profile.refillRatePerSecond shouldBe 10.0
    }
  }

  "TokenBucketState" - {
    "should truncate tokens to int correctly" in {
      val state =
        TokenBucketState(tokens = 95.7, lastRefillMs = 0L, version = 1L)
      state.tokensInt shouldBe 95
    }
  }
