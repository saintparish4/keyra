package resilience

import scala.concurrent.duration.*

import org.scalacheck.Gen
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import cats.effect.*
import cats.effect.testing.scalatest.AsyncIOSpec

class CircuitBreakerPropertySpec
    extends AsyncFreeSpec
    with AsyncIOSpec
    with Matchers
    with ScalaCheckPropertyChecks:

  given Logger[IO] = NoOpLogger[IO]

  val tightConfig: CircuitBreakerConfig = CircuitBreakerConfig(
    maxFailures = 2,
    resetTimeout = 100.millis,
    halfOpenMaxCalls = 1,
  )

  "CircuitBreaker state transition invariants" - {

    // Closed -> Open (never Closed -> HalfOpen directly)
    "starts Closed and transitions to Open only after maxFailures, never HalfOpen directly" in
      CircuitBreaker[IO]("prop-test", tightConfig).flatMap { cb =>
        for
          s0 <- cb.state
          _ <- cb.protect(IO.raiseError(new Exception("fail"))).attempt
          s1 <- cb.state
          _ <- cb.protect(IO.raiseError(new Exception("fail"))).attempt
          s2 <- cb.state
        yield
          s0 shouldBe CircuitState.Closed
          // After 1 failure: still Closed (threshold = 2)
          s1 shouldBe CircuitState.Closed
          // After 2 failures: Open
          s2 shouldBe CircuitState.Open
      }.asserting(identity)

    // Closed state only accepts Closed -> Open, not Closed -> HalfOpen
    "Closed never transitions to HalfOpen without going through Open" in
      CircuitBreaker[IO]("prop-test-2", tightConfig).flatMap(cb =>
        for
          _ <- cb.protect(IO.unit) // success: stays Closed
          s <- cb.state
        yield s shouldBe CircuitState.Closed,
      ).asserting(identity)

    // Open -> HalfOpen after resetTimeout; then HalfOpen -> Closed on success
    "Open -> HalfOpen -> Closed on successful probe" in
      CircuitBreaker[IO]("prop-test-3", tightConfig).flatMap { cb =>
        for
          // Force Open
          _ <- cb.protect(IO.raiseError(new Exception("f"))).attempt
          _ <- cb.protect(IO.raiseError(new Exception("f"))).attempt
          s1 <- cb.state
          // Wait past resetTimeout
          _ <- IO.sleep(150.millis)
          // Successful probe transitions HalfOpen -> Closed
          _ <- cb.protect(IO.unit).attempt
          s2 <- cb.state
        yield
          s1 shouldBe CircuitState.Open
          s2 shouldBe CircuitState.Closed
      }.asserting(identity)

    // HalfOpen -> Open on failed probe (no bypass)
    "HalfOpen -> Open on failed probe" in
      CircuitBreaker[IO]("prop-test-4", tightConfig).flatMap(cb =>
        for
          _ <- cb.protect(IO.raiseError(new Exception("f"))).attempt
          _ <- cb.protect(IO.raiseError(new Exception("f"))).attempt
          _ <- IO.sleep(150.millis)
          // Failed probe transitions HalfOpen -> Open again
          _ <- cb.protect(IO.raiseError(new Exception("f"))).attempt
          s <- cb.state
        yield s shouldBe CircuitState.Open,
      ).asserting(identity)

    "requests are rejected while Open (before resetTimeout)" in
      CircuitBreaker[IO]("prop-test-5", tightConfig).flatMap(cb =>
        for
          _ <- cb.protect(IO.raiseError(new Exception("f"))).attempt
          _ <- cb.protect(IO.raiseError(new Exception("f"))).attempt
          // No sleep: should still be Open and reject
          result <- cb.protect(IO.unit).attempt
        yield result.left.toOption
          .map(_.isInstanceOf[CircuitBreakerOpen]) shouldBe Some(true),
      ).asserting(identity)
  }
