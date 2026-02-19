import cats.effect.*
import cats.effect.std.Console
import cats.syntax.all.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.implicits.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import io.circe.Json
import scala.concurrent.duration.*
import java.util.concurrent.atomic.{AtomicLong, AtomicInteger}

/**
 * Load simulation runner for scalax.
 *
 * Scenarios:
 *   normal        — many unique keys, steady rate, verifies base throughput
 *   burst         — many unique keys with bursts, exercises token refill
 *   idempotency   — repeated idempotency keys, verifies exactly-one-Created
 *   realistic     — mix of rate-limit (80%) and idempotency (20%) traffic
 *   highContention — all requests use a single fixed key at maximum concurrency;
 *                    stresses OCC retry logic and measures retry rate vs throughput
 *
 * Usage:
 *   sbt "loadSim/run --scenario highContention"
 *   sbt "loadSim/run --scenario normal"
 */
object LoadSim extends IOApp:

  val defaultBaseUrl = "http://localhost:8080"
  val defaultApiKey  = "test-api-key"

  override def run(args: List[String]): IO[ExitCode] =
    val scenario = args
      .sliding(2)
      .collectFirst { case "--scenario" :: s :: Nil => s }
      .getOrElse("normal")

    val baseUrl = args
      .sliding(2)
      .collectFirst { case "--url" :: u :: Nil => u }
      .getOrElse(defaultBaseUrl)

    EmberClientBuilder.default[IO].build.use { client =>
      scenario match
        case "normal"        => Scenarios.normal(client, baseUrl).as(ExitCode.Success)
        case "burst"         => Scenarios.burst(client, baseUrl).as(ExitCode.Success)
        case "idempotency"   => Scenarios.idempotency(client, baseUrl).as(ExitCode.Success)
        case "realistic"     => Scenarios.realistic(client, baseUrl).as(ExitCode.Success)
        case "highContention" => Scenarios.highContention(client, baseUrl).as(ExitCode.Success)
        case unknown =>
          Console[IO].errorln(s"Unknown scenario: $unknown. Valid: normal, burst, idempotency, realistic, highContention") *>
            IO.pure(ExitCode.Error)
    }

object Scenarios:
  import Http.*

  def normal(client: Client[IO], baseUrl: String): IO[Unit] =
    val cfg = RunConfig(
      concurrency    = 20,
      durationSecs   = 60,
      description    = "normal — 20 VUs, many unique keys, 60 s",
      keyFn          = i => s"user:${i % 500}",
    )
    runRateLimitScenario(client, baseUrl, cfg)

  def burst(client: Client[IO], baseUrl: String): IO[Unit] =
    val cfg = RunConfig(
      concurrency    = 50,
      durationSecs   = 60,
      description    = "burst — 50 VUs, bursty per-user keys, 60 s",
      keyFn          = i => s"user:${i % 50}",
    )
    runRateLimitScenario(client, baseUrl, cfg)

  def idempotency(client: Client[IO], baseUrl: String): IO[Unit] =
    val total     = new AtomicLong(0)
    val created   = new AtomicLong(0)
    val duplicate = new AtomicLong(0)
    val errors    = new AtomicLong(0)

    val concurrency = 30
    val durationSecs = 30

    val runId = System.currentTimeMillis.toString

    Console[IO].println(s"=== idempotency — $concurrency VUs, repeated keys, ${durationSecs}s (run $runId) ===") *>
    IO.race(
      progressTicker("idempotency", durationSecs, total, created, duplicate, errors),
      (0 until concurrency).toList.parTraverse_ { vuid =>
        val key = s"idem:$runId:key-${vuid % 5}" // 5 shared keys per run, unique across runs
        (for
          res <- sendIdempotencyCheck(client, baseUrl, key)
          _   <- IO(total.incrementAndGet())
          _ <- res match
            case Right("new")         => IO(created.incrementAndGet())
            case Right("in_progress") => IO(duplicate.incrementAndGet())
            case Right("duplicate")   => IO(duplicate.incrementAndGet())
            case _                    => IO(errors.incrementAndGet())
        yield ()).loop
      }
    ) *>
    IO.defer {
      Console[IO].println(
        s"""idempotency results:
           |  total      = ${total.get}
           |  created    = ${created.get}
           |  duplicate  = ${duplicate.get}
           |  errors     = ${errors.get}
           |  assertion  : created should equal number of distinct keys (5 keys = 5 Created expected)
           |""".stripMargin
      )
    }

  def realistic(client: Client[IO], baseUrl: String): IO[Unit] =
    val concurrency  = 40
    val durationSecs = 60
    val description  = "realistic — 40 VUs, 80% rate-limit / 20% idempotency, 60s"
    val keyFn        = (i: Int) => s"user:${i % 200}"

    val total      = new AtomicLong(0)
    val rlAllowed  = new AtomicLong(0)
    val rlBlocked  = new AtomicLong(0)
    val idemCount  = new AtomicLong(0)
    val errors     = new AtomicLong(0)

    Console[IO].println(s"=== $description ===") *>
    IO.race(
      progressTicker("realistic", durationSecs, total, rlAllowed, rlBlocked, errors),
      (0 until concurrency).toList.parTraverse_ { vuid =>
        (for
          roll <- IO(scala.util.Random.nextInt(100))
          _ <-
            if roll < 80 then
              sendRateLimitCheck(client, baseUrl, keyFn(vuid)).flatMap {
                case Right(true)  => IO { total.incrementAndGet(); rlAllowed.incrementAndGet() }
                case Right(false) => IO { total.incrementAndGet(); rlBlocked.incrementAndGet() }
                case Left(_)      => IO { total.incrementAndGet(); errors.incrementAndGet() }
              }
            else
              sendIdempotencyCheck(client, baseUrl, s"idem:${vuid}:${System.currentTimeMillis / 10000}").flatMap {
                case Right(_) => IO { total.incrementAndGet(); idemCount.incrementAndGet() }
                case Left(_)  => IO { total.incrementAndGet(); errors.incrementAndGet() }
              }
        yield ()).loop
      }
    ) *>
    IO.defer {
      val t = total.get
      val rl = rlAllowed.get + rlBlocked.get
      val id = idemCount.get
      Console[IO].println(
        s"""$description results:
           |  total        = $t  (~${t / durationSecs} RPS)
           |  rate-limit   = $rl  (allowed=${rlAllowed.get}, blocked=${rlBlocked.get})
           |  idempotency  = $id
           |  errors       = ${errors.get}
           |  split        = ${if t > 0 then rl * 100 / t else 0}% rate-limit / ${if t > 0 then id * 100 / t else 0}% idempotency
           |""".stripMargin
      )
    }

  /**
   * highContention: all VUs hammer the same single key with maximum concurrency.
   *
   * Purpose: stress the OCC retry path. With N concurrent writers on one DynamoDB
   * item, all but one will get ConditionalCheckFailedException per round-trip and
   * must retry. This measures:
   *   - throughput degradation as concurrency rises
   *   - OCC retry rate (visible in RateLimitOCCRetry CloudWatch metric)
   *   - tail latency under contention
   *
   * Expected behaviour:
   *   - Allowed count stays close to the configured capacity (tokens per second * window);
   *     most requests are rejected (429) after the bucket empties.
   *   - A fraction of 429s are due to OCC exhaustion (not just empty bucket) — these
   *     are observable via the RateLimitOCCRetry metric counter.
   *   - Latency P99 rises compared to the normal scenario due to retries.
   */
  def highContention(client: Client[IO], baseUrl: String): IO[Unit] =
    val concurrency  = 50
    val durationSecs = 60
    val fixedKey     = "contention:single-hot-key"

    val total       = new AtomicLong(0)
    val allowed     = new AtomicLong(0)
    val blocked     = new AtomicLong(0)
    val errors      = new AtomicLong(0)
    val latencySum  = new AtomicLong(0)
    val latencyMax  = new AtomicLong(0)

    Console[IO].println(
      s"""=== highContention — $concurrency VUs, single key "$fixedKey", ${durationSecs}s ===
         |All requests target the same DynamoDB item. OCC retry rate will be high.
         |Watch: RateLimitOCCRetry metric, tail latency, and allowed vs blocked counts.
         |""".stripMargin
    ) *>
    IO.race(
      progressTicker("highContention", durationSecs, total, allowed, blocked, errors),
      (0 until concurrency).toList.parTraverse_ { _ =>
        (for
          t0  <- IO(System.currentTimeMillis)
          res <- sendRateLimitCheck(client, baseUrl, fixedKey)
          t1  <- IO(System.currentTimeMillis)
          ms   = t1 - t0
          _   <- IO {
            total.incrementAndGet()
            latencySum.addAndGet(ms)
            var prev = latencyMax.get
            while ms > prev && !latencyMax.compareAndSet(prev, ms) do prev = latencyMax.get
            res match
              case Right(true)  => allowed.incrementAndGet()
              case Right(false) => blocked.incrementAndGet()
              case Left(_)      => errors.incrementAndGet()
          }
        yield ()).loop
      }
    ) *>
    IO {
      val t   = total.get
      val avg = if t > 0 then latencySum.get / t else 0
      (t, avg)
    }.flatMap { (t, avgMs) =>
      Console[IO].println(
        s"""highContention results (${durationSecs}s window):
           |  total requests = $t  (${t / durationSecs} RPS)
           |  allowed        = ${allowed.get}  (${if t > 0 then allowed.get * 100 / t else 0}%)
           |  blocked (429)  = ${blocked.get}  (${if t > 0 then blocked.get * 100 / t else 0}%)
           |  errors         = ${errors.get}
           |  avg latency    = ${avgMs}ms
           |  max latency    = ${latencyMax.get}ms
           |
           |OCC retry rate is visible in CloudWatch metric RateLimitOCCRetry.
           |High blocked% under a single hot key is expected — the bucket empties quickly
           |and OCC exhaustion (after 10 retries) contributes additional rejections.
           |Compare with 'normal' scenario to see latency cost of contention.
           |""".stripMargin
      )
    }

  // --- Shared helpers ---

  private val ProgressIntervalSecs = 5

  private def progressTicker(
    label:    String,
    duration: Int,
    total:    AtomicLong,
    allowed:  AtomicLong,
    blocked:  AtomicLong,
    errors:   AtomicLong,
  ): IO[Unit] =
    def tick(elapsed: Int): IO[Unit] =
      if elapsed >= duration then IO.unit
      else
        IO.sleep(ProgressIntervalSecs.seconds) *>
          IO.defer {
            val e   = elapsed + ProgressIntervalSecs
            val t   = total.get
            val rps = if e > 0 then t / e else 0
            Console[IO].println(
              f"  [$label] ${e}s / ${duration}s  |  total=$t  allowed=${allowed.get}  blocked=${blocked.get}  errors=${errors.get}  (~${rps} RPS)"
            ) *> tick(e)
          }
    tick(0)

  private case class RunConfig(
    concurrency:  Int,
    durationSecs: Int,
    description:  String,
    keyFn:        Int => String,
  )

  private def runRateLimitScenario(
    client:  Client[IO],
    baseUrl: String,
    cfg:     RunConfig,
  ): IO[Unit] =
    val total   = new AtomicLong(0)
    val allowed = new AtomicLong(0)
    val blocked = new AtomicLong(0)
    val errors  = new AtomicLong(0)

    Console[IO].println(s"=== ${cfg.description} ===") *>
    IO.race(
      progressTicker(cfg.description.takeWhile(_ != ' '), cfg.durationSecs, total, allowed, blocked, errors),
      (0 until cfg.concurrency).toList.parTraverse_ { vuid =>
        (sendRateLimitCheck(client, baseUrl, cfg.keyFn(vuid)).flatMap {
          case Right(true)  => IO { total.incrementAndGet(); allowed.incrementAndGet() }
          case Right(false) => IO { total.incrementAndGet(); blocked.incrementAndGet() }
          case Left(_)      => IO { total.incrementAndGet(); errors.incrementAndGet() }
        }).loop
      }
    ) *>
    printRateLimitResults(cfg.description, total, allowed, blocked, errors, None, None)

  private def printRateLimitResults(
    desc:    String,
    total:   AtomicLong,
    allowed: AtomicLong,
    blocked: AtomicLong,
    errors:  AtomicLong,
    avgMs:   Option[Long],
    maxMs:   Option[Long],
  ): IO[Unit] =
    IO {
      val t = total.get
      s"""$desc results:
         |  total     = $t
         |  allowed   = ${allowed.get}  (${if t > 0 then allowed.get * 100 / t else 0}%)
         |  blocked   = ${blocked.get}  (${if t > 0 then blocked.get * 100 / t else 0}%)
         |  errors    = ${errors.get}
         |${avgMs.fold("")(ms => s"  avg lat   = ${ms}ms\n")}${maxMs.fold("")(ms => s"  max lat   = ${ms}ms\n")}""".stripMargin
    }.flatMap(Console[IO].println)

object Http:
  case class RateLimitRequest(key: String, cost: Int = 1)
  case class RateLimitResponse(allowed: Boolean)
  case class IdempotencyRequest(idempotencyKey: String, ttl: Int = 3600)
  case class IdempotencyResponse(status: String)

  def sendRateLimitCheck(client: Client[IO], baseUrl: String, key: String): IO[Either[String, Boolean]] =
    val body = Json.obj("key" := key, "cost" := 1)
    val req  = Request[IO](
      method  = Method.POST,
      uri     = Uri.unsafeFromString(s"$baseUrl/v1/ratelimit/check"),
      headers = Headers(
        "Content-Type"  -> "application/json",
        "Authorization" -> s"Bearer ${LoadSim.defaultApiKey}",
      ),
    ).withEntity(body.noSpaces)

    client.run(req).use { resp =>
      resp.as[String].map { body =>
        if resp.status.code == 200 || resp.status.code == 429 then
          io.circe.parser.parse(body)
            .flatMap(_.hcursor.get[Boolean]("allowed"))
            .left.map(_.getMessage)
        else
          Left(s"HTTP ${resp.status.code}: $body")
      }
    }.handleError(e => Left(e.getMessage))

  def sendIdempotencyCheck(client: Client[IO], baseUrl: String, key: String): IO[Either[String, String]] =
    val body = Json.obj("idempotencyKey" := key, "ttl" := 3600)
    val req  = Request[IO](
      method  = Method.POST,
      uri     = Uri.unsafeFromString(s"$baseUrl/v1/idempotency/check"),
      headers = Headers(
        "Content-Type"  -> "application/json",
        "Authorization" -> s"Bearer ${LoadSim.defaultApiKey}",
      ),
    ).withEntity(body.noSpaces)

    client.run(req).use { resp =>
      resp.as[String].map { body =>
        if resp.status.code == 200 then
          io.circe.parser.parse(body)
            .flatMap(_.hcursor.get[String]("status"))
            .left.map(_.getMessage)
        else
          Left(s"HTTP ${resp.status.code}: $body")
      }
    }.handleError(e => Left(e.getMessage))

// IO.loop helper — run an action forever until cancelled
extension [A](io: IO[A])
  def loop: IO[Nothing] = io.foreverM
