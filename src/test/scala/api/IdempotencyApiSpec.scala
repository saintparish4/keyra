package api

import java.time.Instant

import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.*
import org.typelevel.log4cats.Logger

import config.IdempotencyConfig
import core.*
import events.EventPublisher
import _root_.metrics.MetricsPublisher
import security.AuthenticatedClient
import cats.effect.*
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import io.circe.parser.*

/** Unit tests for IdempotencyApi: TTL capping and warning when client TTL
  * exceeds max.
  */
class IdempotencyApiSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers:

  val testClient: AuthenticatedClient = AuthenticatedClient(
    apiKeyId = "test-client",
    clientId = "test-client",
    clientName = "Test",
    tier = security.ClientTier.Free,
    permissions = security.Permission.standard,
  )

  "IdempotencyApi TTL capping" - {

    "should pass capped TTL to store when client requests TTL above max" in {
      val maxTtl = 3600L
      val requestedTtl = 100000L
      val config =
        IdempotencyConfig(defaultTtlSeconds = 86400, maxTtlSeconds = maxTtl)

      val test =
        for
          capturedTtl <- Ref[IO].of(Option.empty[Long])
          store = new IdempotencyStore[IO]:
            override def check(
                idempotencyKey: String,
                clientId: String,
                ttlSeconds: Long,
            ): IO[IdempotencyResult] = capturedTtl.set(Some(ttlSeconds)) *>
              Clock[IO].realTime.map(d =>
                IdempotencyResult
                  .New(idempotencyKey, Instant.ofEpochMilli(d.toMillis)),
              )
            override def storeResponse(
                idempotencyKey: String,
                response: StoredResponse,
            ): IO[Boolean] = IO.pure(false)
            override def markFailed(idempotencyKey: String): IO[Boolean] = IO
              .pure(false)
            override def get(
                idempotencyKey: String,
            ): IO[Option[IdempotencyRecord]] = IO.pure(None)
            override def healthCheck: IO[Either[String, Unit]] = IO.pure(Right(()))

          logger <- Ref[IO].of(List.empty[String])
            .map(ref => capturingLogger(ref))
          api = IdempotencyApi[IO](
            store,
            config,
            EventPublisher.noop[IO],
            MetricsPublisher.noop[IO],
            logger,
          )
          body = s"""{"idempotencyKey": "cap-test", "ttl": $requestedTtl}"""
          req = Request[IO](Method.POST, uri"/v1/idempotency/check")
            .withEntity(body).putHeaders(
              headers.`Content-Type`(MediaType.application.json),
              headers.Authorization(Credentials.Token(ci"Bearer", "test-key")),
            )
          _ <- api.check(req, testClient)
          ttl <- capturedTtl.get
        yield ttl

      test.asserting(ttl => ttl shouldBe Some(maxTtl))
    }
  }

  "IdempotencyApi TTL warning" - {

    "should log a warning when client TTL exceeds max" in {
      val maxTtl = 3600L
      val requestedTtl = 99999L
      val config =
        IdempotencyConfig(defaultTtlSeconds = 86400, maxTtlSeconds = maxTtl)

      val test =
        for
          warnLogs <- Ref[IO].of(List.empty[String])
          logger = capturingLogger(warnLogs)
          store = new IdempotencyStore[IO]:
            override def check(
                idempotencyKey: String,
                clientId: String,
                ttlSeconds: Long,
            ): IO[IdempotencyResult] = Clock[IO].realTime.map(d =>
              IdempotencyResult
                .New(idempotencyKey, Instant.ofEpochMilli(d.toMillis)),
            )
            override def storeResponse(
                idempotencyKey: String,
                response: StoredResponse,
            ): IO[Boolean] = IO.pure(false)
            override def markFailed(idempotencyKey: String): IO[Boolean] = IO
              .pure(false)
            override def get(
                idempotencyKey: String,
            ): IO[Option[IdempotencyRecord]] = IO.pure(None)
            override def healthCheck: IO[Either[String, Unit]] = IO.pure(Right(()))

          api = IdempotencyApi[IO](
            store,
            config,
            EventPublisher.noop[IO],
            MetricsPublisher.noop[IO],
            logger,
          )
          body = s"""{"idempotencyKey": "warn-test", "ttl": $requestedTtl}"""
          req = Request[IO](Method.POST, uri"/v1/idempotency/check")
            .withEntity(body).putHeaders(
              headers.`Content-Type`(MediaType.application.json),
              headers.Authorization(Credentials.Token(ci"Bearer", "test-key")),
            )
          _ <- api.check(req, testClient)
          logs <- warnLogs.get
        yield logs

      test.asserting { logs =>
        logs should have size 1
        logs.head should include("Idempotency TTL capped")
        logs.head should include("requested=99999")
        logs.head should include("max=3600")
        logs.head should include("warn-test")
      }
    }
  }

  /** Logger that appends warn messages to a Ref for test assertions. */
  private def capturingLogger(warnLogs: Ref[IO, List[String]]): Logger[IO] =
    new Logger[IO]:
      override def error(t: Throwable)(message: => String): IO[Unit] = IO.unit
      override def error(message: => String): IO[Unit] = IO.unit
      override def warn(t: Throwable)(message: => String): IO[Unit] = warnLogs
        .update(_ :+ message)
      override def warn(message: => String): IO[Unit] = warnLogs
        .update(_ :+ message)
      override def info(t: Throwable)(message: => String): IO[Unit] = IO.unit
      override def info(message: => String): IO[Unit] = IO.unit
      override def debug(t: Throwable)(message: => String): IO[Unit] = IO.unit
      override def debug(message: => String): IO[Unit] = IO.unit
      override def trace(t: Throwable)(message: => String): IO[Unit] = IO.unit
      override def trace(message: => String): IO[Unit] = IO.unit
