package resilience

    import scala.concurrent.duration.*

    import org.scalatest.freespec.AsyncFreeSpec
    import org.scalatest.matchers.should.Matchers
    import org.typelevel.log4cats.Logger
    import org.typelevel.log4cats.noop.NoOpLogger

    import cats.effect.*
    import cats.effect.testing.scalatest.AsyncIOSpec
    import config.*
    import core.*
    import events.EventPublisher
    import _root_.metrics.MetricsPublisher
    import testutil.*

    class ResilientRateLimitStoreSpec
        extends AsyncFreeSpec with AsyncIOSpec with Matchers:

      given Logger[IO] = NoOpLogger[IO]

      val enabledConfig: ResilienceConfig = ResilienceConfig(
        circuitBreaker = CircuitBreakerSettings(
          enabled = true,
          dynamodb = config.CircuitBreakerConfig(
            maxFailures = 3,
            resetTimeout = 5.seconds,
            halfOpenMaxCalls = 2,
          ),
          kinesis = config.CircuitBreakerConfig(),
        ),
        retry = RetrySettings(
          dynamodb = RetryConfig(
            maxRetries = 2,
            baseDelay = 10.millis,
            maxDelay = 100.millis,
            multiplier = 2.0,
          ),
          kinesis = RetryConfig(),
        ),
        bulkhead = BulkheadSettings(
          enabled = true,
          maxConcurrent = 2,
          maxWait = 50.millis,
        ),
        timeout = TimeoutSettings(rateLimitCheck = 1.second),
      )

      "ResilientRateLimitStore with patterns enabled" - {

        "opens circuit breaker after 3 consecutive failures" in pending

        "transitions from Open to HalfOpen after reset timeout" in pending

        "closes circuit breaker after successes in HalfOpen" in pending

        "bulkhead rejects when at max concurrency" in pending

        "retries exhausted returns degraded decision" in pending
      }