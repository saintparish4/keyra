package core

import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class TokenBucketPropertySpec
    extends AnyFreeSpec with ScalaCheckPropertyChecks with Matchers:

  // Generators ---------------------------------------------------------------

  val genCapacity: Gen[Int] = Gen.choose(1, 1000)

  val genProfile: Gen[RateLimitProfile] =
    for
      capacity <- genCapacity
      rate <- Gen.choose(1, 100).map(_.toDouble)
    yield RateLimitProfile(capacity, rate, ttlSeconds = 3600)

  def genState(maxCapacity: Int): Gen[TokenBucketState] =
    for
      tokens <- Gen.choose(0.0, maxCapacity.toDouble)
      lastRefill <- Gen.choose(0L, 1_000_000_000L)
      version <- Gen.choose(0L, Long.MaxValue / 2)
    yield TokenBucketState(tokens, lastRefill, version)

  val genNowMs: Gen[Long] = Gen.choose(0L, 2_000_000_000L)
  val genCost: Gen[Int] = Gen.choose(1, 500)

  // Invariants ---------------------------------------------------------------

  "TokenBucket properties" - {

    "tokens never go negative after any refill+consume sequence" in
      forAll(genProfile, genNowMs, genCost)((profile, nowMs, cost) =>
        forAll(genState(profile.capacity)) { state =>
          val refilled = TokenBucket.refill(state, nowMs, profile)
          val maybeConsumed = TokenBucket.consume(refilled, cost, nowMs)
          maybeConsumed.foreach(s => s.tokens should be >= 0.0)
          succeed
        },
      )

    "refill never exceeds capacity for any elapsed time" in
      forAll(genProfile, genNowMs)((profile, nowMs) =>
        forAll(genState(profile.capacity)) { state =>
          val result = TokenBucket.refill(state, nowMs, profile)
          result.tokens should be <= profile.capacity.toDouble + 1e-9
        },
      )

    "refill is monotonically non-decreasing: refill(t2) >= refill(t1) when t2 >= t1" in
      forAll(genProfile)(profile =>
        forAll(genState(profile.capacity))(state =>
          forAll(genNowMs)(t1 =>
            forAll(Gen.choose(t1, t1 + 10_000_000L)) { t2 =>
              val r1 = TokenBucket.refill(state, t1, profile)
              val r2 = TokenBucket.refill(state, t2, profile)
              // r2 >= r1 unless both already at capacity (in which case equal)
              r2.tokens should be >= r1.tokens - 1e-9
            },
          ),
        ),
      )

    "consume reduces tokens by exactly cost on success" in forAll(
      genProfile,
      genNowMs,
    )((profile, nowMs) =>
      forAll(genCost) { cost =>
        // Give the state more tokens than cost so consume always succeeds
        val state =
          TokenBucketState(cost.toDouble + 1.0, lastRefillMs = 0L, version = 1L)
        val result = TokenBucket.consume(state, cost, nowMs)
        result shouldBe defined
        result.get.tokens shouldBe state.tokens - cost +- 1e-9
      },
    )

    "version increments by exactly 1 on each successful consume" in
      forAll(genNowMs, genCost, Gen.choose(0L, Long.MaxValue - 1)) {
        (nowMs, cost, version) =>
          val state = TokenBucketState(
            cost.toDouble + 1.0,
            lastRefillMs = 0L,
            version = version,
          )
          val result = TokenBucket.consume(state, cost, nowMs)
          result shouldBe defined
          result.get.version shouldBe version + 1L
      }

    "full refill: after sufficient time from empty, bucket is at capacity" in
      forAll(genProfile) { profile =>
        val state =
          TokenBucketState(tokens = 0.0, lastRefillMs = 0L, version = 1L)
        val secondsNeeded = math
          .ceil(profile.capacity.toDouble / profile.refillRatePerSecond).toLong
        val result = TokenBucket
          .refill(state, nowMs = (secondsNeeded + 1) * 1000L, profile)
        result.tokens shouldBe profile.capacity.toDouble +- 1e-9
      }

    "consume returns None when cost exceeds available tokens" in
      forAll(genNowMs, Gen.choose(1, 50)) { (nowMs, cost) =>
        val state = TokenBucketState(
          tokens = cost.toDouble - 0.5,
          lastRefillMs = 0L,
          version = 1L,
        )
        val result = TokenBucket.consume(state, cost, nowMs)
        result shouldBe None
      }
  }
