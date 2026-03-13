package resilience

import scala.concurrent.duration.*

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import cats.effect.*
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.testkit.TestControl
import storage.InMemoryRateLimitStore
import core.*

/** Deterministic time tests using cats.effect.testkit.TestControl.
  *
  * These tests verify time-sensitive behaviour without Thread.sleep or real
  * wall-clock delays. TestControl governs the virtual clock; calls to IO.sleep
  * and Clock[IO].realTime both use that virtual time.
  */
class DeterministicTimeSpec
    extends AsyncFreeSpec with AsyncIOSpec with Matchers:

  given Logger[IO] = NoOpLogger[IO]

  "RetryPolicy backoff timing" - {

    "total virtual time elapsed equals sum of exponential delays (jitter=0)" in {
      // With jitterFactor=0, delays are exactly: 100ms, 200ms, 400ms → 700ms total
      val policy = RetryPolicy(
        maxRetries = 3,
        baseDelay = 100.millis,
        maxDelay = 10.seconds,
        multiplier = 2.0,
        jitterFactor = 0.0,
        retryOn = _ => true,
      )
      val ex = new RuntimeException("always fail")

      val test =
        for
          t0 <- Clock[IO].realTime.map(_.toMillis)
          _ <- Retry.withPolicy[IO, Unit](policy, "test")(IO.raiseError(ex))
            .attempt
          t1 <- Clock[IO].realTime.map(_.toMillis)
        yield t1 - t0

      TestControl.executeEmbed(test).asserting(_ shouldBe 700L)
    }

    "partial retry succeeds before exhaustion — only failed delays are counted" in {
      // Fail twice (200ms), succeed on attempt 3
      val policy = RetryPolicy(
        maxRetries = 5,
        baseDelay = 100.millis,
        maxDelay = 10.seconds,
        multiplier = 2.0,
        jitterFactor = 0.0,
        retryOn = _ => true,
      )

      val test =
        for
          counter <- Ref.of[IO, Int](0)
          t0 <- Clock[IO].realTime.map(_.toMillis)
          _ <- Retry.withPolicy[IO, Unit](policy, "test")(
            counter.getAndUpdate(_ + 1).flatMap(n =>
              if n < 2 then IO.raiseError(new RuntimeException("fail"))
              else IO.unit,
            ),
          )
          t1 <- Clock[IO].realTime.map(_.toMillis)
        yield t1 - t0

      // delays for failed attempts 1 and 2: 100ms + 200ms = 300ms
      TestControl.executeEmbed(test).asserting(_ shouldBe 300L)
    }
  }

  "CircuitBreaker open->halfOpen transition" - {

    "transitions to HalfOpen after exactly resetTimeout, not before" in {
      val config = CircuitBreakerConfig(
        maxFailures = 1,
        resetTimeout = 1.second,
        halfOpenMaxCalls = 1,
      )

      val test =
        for
          cb <- CircuitBreaker[IO]("det-test", config)
          // Force Open
          _ <- cb.protect(IO.raiseError(new Exception("fail"))).attempt
          s0 <- cb.state

          // Advance clock to just before resetTimeout — should still be Open
          _ <- IO.sleep(999.millis)
          // Trigger the Open -> HalfOpen check (but not yet enough time)
          r1 <- cb.protect(IO.unit).attempt // rejected as CircuitBreakerOpen
          s1 <- cb.state

          // Advance past resetTimeout
          _ <- IO.sleep(2.millis)
          // Successful probe: Open -> HalfOpen -> Closed
          _ <- cb.protect(IO.unit).attempt
          s2 <- cb.state
        yield (s0, s1, s2)

      TestControl.executeEmbed(test).asserting { case (s0, s1, s2) =>
        s0 shouldBe CircuitState.Open
        s1 shouldBe CircuitState.Open // still Open before timeout
        s2 shouldBe CircuitState.Closed // Closed after successful probe
      }
    }
  }

  "Token bucket refill over time" - {

    "bucket refills correct token count after known elapsed intervals" in {
      val profile = RateLimitProfile(
        capacity = 10,
        refillRatePerSecond = 1.0,
        ttlSeconds = 3600,
      )

      val test =
        for
          store <- InMemoryRateLimitStore.create[IO]

          // Drain the bucket completely
          d0 <- store.checkAndConsume("user:1", 10, profile)

          // Immediately try again — should be rejected
          d1 <- store.checkAndConsume("user:1", 1, profile)

          // Advance 5 virtual seconds (5 tokens should refill)
          _ <- IO.sleep(5.seconds)
          d2 <- store.checkAndConsume("user:1", 5, profile)

          // One more immediately — only 0 tokens left, should reject
          d3 <- store.checkAndConsume("user:1", 1, profile)
        yield (d0, d1, d2, d3)

      TestControl.executeEmbed(test).asserting { case (d0, d1, d2, d3) =>
        d0 shouldBe a[RateLimitDecision.Allowed]
        d1 shouldBe a[RateLimitDecision.Rejected]
        d2 shouldBe a[RateLimitDecision.Allowed]
        d3 shouldBe a[RateLimitDecision.Rejected]
      }
    }

    "bucket does not over-refill beyond capacity" in {
      val profile = RateLimitProfile(
        capacity = 10,
        refillRatePerSecond = 1.0,
        ttlSeconds = 3600,
      )

      val test =
        for
          store <- InMemoryRateLimitStore.create[IO]
          // Drain 5 tokens
          _ <- store.checkAndConsume("user:2", 5, profile)
          // Wait 100 seconds (would be 100 tokens, but capacity caps at 10)
          _ <- IO.sleep(100.seconds)
          // Consume the max capacity — should succeed if bucket is full
          d <- store.checkAndConsume("user:2", 10, profile)
        yield d

      TestControl.executeEmbed(test)
        .asserting(_ shouldBe a[RateLimitDecision.Allowed])
    }
  }
