package api

    import org.http4s.*
    import org.http4s.implicits.*
    import org.scalatest.freespec.AsyncFreeSpec
    import org.scalatest.matchers.should.Matchers
    import org.typelevel.ci.*
    import org.typelevel.log4cats.noop.NoOpLogger
    import org.typelevel.log4cats.Logger

    import cats.effect.*
    import cats.effect.testing.scalatest.AsyncIOSpec
    import config.{RateLimitConfig, RateLimitProfileConfig}
    import core.*
    import events.EventPublisher
    import observability.MetricsPublisher
    import testutil.*

    class RateLimitApiSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers:

      given Logger[IO] = NoOpLogger[IO]

      val configWithProfiles: RateLimitConfig = RateLimitConfig(
        defaultCapacity = 50,
        defaultRefillRatePerSecond = 5.0,
        defaultTtlSeconds = 3600,
        algorithm = "token-bucket",
        profiles = Map(
          "free" -> RateLimitProfileConfig(20, 2.0, 3600),
          "custom" -> RateLimitProfileConfig(500, 50.0, 7200),
        ),
      )

      "RateLimitApi" - {

        "rejects cost=0 with 400 BadRequest" in pending

        "rejects negative cost with 400 BadRequest" in pending

        "resolves profile by explicit name" in pending

        "resolves profile by tier name from config" in pending

        "falls back to config defaults" in pending

        "Allowed response has X-RateLimit-* headers" in pending

        "Rejected response has Retry-After header" in pending
      }