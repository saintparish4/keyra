package core

import java.time.Instant

import scala.concurrent.duration.*

import org.typelevel.log4cats.Logger

import cats.effect.*
import cats.syntax.all.*
import _root_.metrics.MetricsPublisher
import config.TokenQuotaConfig

sealed trait QuotaLevel:
  def prefix: String

object QuotaLevel:
  case object User extends QuotaLevel:
    val prefix = "user"
  case object Agent extends QuotaLevel:
    val prefix = "agent"
  case object Org extends QuotaLevel:
    val prefix = "org"

  val all: List[QuotaLevel] = List(User, Agent, Org)

case class QuotaIdentifier(
    userId: String,
    agentId: Option[String] = None,
    orgId: Option[String] = None,
)

sealed trait QuotaDecision
object QuotaDecision:
  case class Available(remainingByLevel: Map[QuotaLevel, Long])
      extends QuotaDecision

  case class Exceeded(
      level: QuotaLevel,
      limit: Long,
      used: Long,
      retryAfterSeconds: Int,
  ) extends QuotaDecision

case class TokenQuotaState(
    inputTokens: Long,
    outputTokens: Long,
    windowStart: Long,
    version: Long,
):
  def totalTokens: Long = inputTokens + outputTokens

trait TokenQuotaStore[F[_]]:
  def getQuota(pk: String): F[Option[TokenQuotaState]]

  def incrementQuota(
      pk: String,
      inputTokensDelta: Long,
      outputTokensDelta: Long,
      windowSeconds: Long,
      nowMs: Long,
  ): F[Boolean]

  def healthCheck: F[Either[String, Unit]]

trait TokenQuotaService[F[_]]:
  def checkQuota(
      identifier: QuotaIdentifier,
      estimatedInputTokens: Long,
      estimatedOutputTokens: Long,
  ): F[QuotaDecision]

  def reconcile(
      identifier: QuotaIdentifier,
      actualInputTokens: Long,
      actualOutputTokens: Long,
      estimatedInputTokens: Long,
      estimatedOutputTokens: Long,
  ): F[Unit]

object TokenQuotaService:
  def apply[F[_]: Async](
      store: TokenQuotaStore[F],
      config: TokenQuotaConfig,
      metrics: MetricsPublisher[F],
      logger: Logger[F],
  ): TokenQuotaService[F] = new TokenQuotaService[F]:

    override def checkQuota(
        identifier: QuotaIdentifier,
        estimatedInputTokens: Long,
        estimatedOutputTokens: Long,
    ): F[QuotaDecision] =
      val estimatedTotal = estimatedInputTokens + estimatedOutputTokens
      val checks = buildLevelChecks(identifier)

      checks.foldLeftM[F, QuotaDecision](
        QuotaDecision.Available(Map.empty): QuotaDecision,
      ) {
        case (
              QuotaDecision.Available(remaining),
              (level, pk, limit, windowSec),
            ) =>
          for
            nowMs <- Clock[F].realTime.map(_.toMillis)
            state <- store.getQuota(pk)
            currentUsed = state
              .filter(s => isWithinWindow(s.windowStart, nowMs, windowSec))
              .map(_.totalTokens).getOrElse(0L)
          yield
            if currentUsed + estimatedTotal > limit then
              val retryAfter = windowSec.toInt
              QuotaDecision.Exceeded(level, limit, currentUsed, retryAfter)
            else
              QuotaDecision.Available(
                remaining + (level -> (limit - currentUsed - estimatedTotal)),
              )

        case (exceeded: QuotaDecision.Exceeded, _) => Async[F].pure(exceeded)
      }.flatTap {
        case QuotaDecision.Available(_) =>
          val checks2 = buildLevelChecks(identifier)
          checks2.traverse_ { case (level, pk, _, windowSec) =>
            for
              nowMs <- Clock[F].realTime.map(_.toMillis)
              success <- store.incrementQuota(
                pk,
                estimatedInputTokens,
                estimatedOutputTokens,
                windowSec,
                nowMs,
              )
              _ <-
                if !success then
                  logger.warn(
                    s"OCC conflict reserving quota for $pk, retries exhausted",
                  )
                else Async[F].unit
            yield ()
          }
        case QuotaDecision.Exceeded(level, limit, used, _) => metrics
            .increment("TokenQuotaExceeded", Map("level" -> level.prefix)) *>
            logger.info(s"Quota exceeded at ${level
                .prefix} level: used=$used, limit=$limit")
      }

    override def reconcile(
        identifier: QuotaIdentifier,
        actualInputTokens: Long,
        actualOutputTokens: Long,
        estimatedInputTokens: Long,
        estimatedOutputTokens: Long,
    ): F[Unit] =
      val inputDelta = actualInputTokens - estimatedInputTokens
      val outputDelta = actualOutputTokens - estimatedOutputTokens
      if inputDelta == 0 && outputDelta == 0 then Async[F].unit
      else
        val checks = buildLevelChecks(identifier)
        checks.traverse_ { case (level, pk, _, windowSec) =>
          for
            nowMs <- Clock[F].realTime.map(_.toMillis)
            _ <- store
              .incrementQuota(pk, inputDelta, outputDelta, windowSec, nowMs)
            _ <- metrics.gauge(
              "keyra_tokens_consumed",
              (actualInputTokens + actualOutputTokens).toDouble,
              Map("level" -> level.prefix) ++ identifierDims(identifier, level),
            )
          yield ()
        }

    private def buildLevelChecks(
        id: QuotaIdentifier,
    ): List[(QuotaLevel, String, Long, Long)] =
      val userCheck = List((
        QuotaLevel.User,
        quotaPk(QuotaLevel.User, id.userId, config.userWindowSeconds),
        config.userLimit,
        config.userWindowSeconds,
      ))
      val agentCheck = id.agentId.toList.map { aid =>
        val effectiveLimit = math
          .min(config.agentLimit, (config.userLimit * 0.8).toLong)
        (
          QuotaLevel.Agent,
          quotaPk(QuotaLevel.Agent, aid, config.agentWindowSeconds),
          effectiveLimit,
          config.agentWindowSeconds,
        )
      }
      val orgCheck = id.orgId.toList.map(oid =>
        (
          QuotaLevel.Org,
          quotaPk(QuotaLevel.Org, oid, config.orgWindowSeconds),
          config.orgLimit,
          config.orgWindowSeconds,
        ),
      )
      userCheck ++ agentCheck ++ orgCheck

    private def quotaPk(level: QuotaLevel, id: String, windowSec: Long): String =
      s"${level.prefix}:$id:${windowSec}s"

    private def isWithinWindow(
        windowStart: Long,
        nowMs: Long,
        windowSec: Long,
    ): Boolean = nowMs - windowStart < windowSec * 1000

    private def identifierDims(
        id: QuotaIdentifier,
        level: QuotaLevel,
    ): Map[String, String] = level match
      case QuotaLevel.User => Map("user" -> id.userId)
      case QuotaLevel.Agent => Map("agent" -> id.agentId.getOrElse("unknown"))
      case QuotaLevel.Org => Map("org" -> id.orgId.getOrElse("unknown"))
