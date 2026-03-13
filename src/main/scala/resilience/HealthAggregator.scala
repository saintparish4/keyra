package resilience

import cats.effect.*
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*

case class ComponentHealth(
    name: String,
    status: String,
    details: Option[Json] = None,
)

case class AggregateHealth(
    status: String, // "ok" or "degraded"
    components: List[ComponentHealth],
):
  def isHealthy: Boolean = status == "ok"

object AggregateHealth:
  given Encoder[ComponentHealth] = Encoder
    .forProduct3("name", "status", "details")(c => (c.name, c.status, c.details))
  given Encoder[AggregateHealth] = Encoder
    .forProduct2("status", "components")(a => (a.status, a.components))

trait HealthSource[F[_]]:
  def name: String
  def check: F[ComponentHealth]

object HealthAggregator:

  def aggregate[F[_]: Temporal](
      sources: List[HealthSource[F]],
  ): F[AggregateHealth] = sources.traverse(_.check).map { components =>
    val allOk = components.forall(_.status == "ok")
    AggregateHealth(
      status = if allOk then "ok" else "degraded",
      components = components,
    )
  }

  def dynamoDbSource[F[_]: Temporal](
      name: String,
      healthCheck: F[Either[String, Unit]],
  ): HealthSource[F] =
    val sourceName = name
    new HealthSource[F]:
      def name: String = sourceName
      def check: F[ComponentHealth] = healthCheck
        .handleError(e => Left(e.getMessage)).map {
          case Right(_) => ComponentHealth(sourceName, "ok")
          case Left(err) =>
            ComponentHealth(sourceName, "error", Some(Json.fromString(err)))
        }

  def circuitBreakerSource[F[_]: Temporal](
      cb: Option[CircuitBreaker[F]],
  ): HealthSource[F] = new HealthSource[F]:
    val name = "circuitBreaker"
    def check: F[ComponentHealth] = cb match
      case Some(breaker) => breaker.state.map(s =>
          ComponentHealth(
            name,
            s.toString.toLowerCase,
            Some(Json.fromString(s.toString)),
          ),
        )
      case None => Temporal[F].pure(ComponentHealth(name, "disabled"))

  def cacheSource[F[_]: Temporal](
      cache: Option[LocalCache[F, ?, ?]],
  ): HealthSource[F] = new HealthSource[F]:
    val name = "cache"
    def check: F[ComponentHealth] = cache match
      case Some(c) => c.stats.map(s =>
          ComponentHealth(
            name,
            "ok",
            Some(Json.obj(
              "hitRate" -> Json.fromDoubleOrNull(s.hitRate),
              "size" -> Json.fromLong(s.estimatedSize),
            )),
          ),
        )
      case None => Temporal[F].pure(ComponentHealth(name, "disabled"))

  def kinesisSource[F[_]: Temporal](
      healthCheck: F[Either[String, Unit]],
  ): HealthSource[F] = new HealthSource[F]:
    val name = "kinesis"
    def check: F[ComponentHealth] = healthCheck
      .handleError(e => Left(e.getMessage)).map {
        case Right(_) => ComponentHealth(name, "ok")
        case Left(err) =>
          ComponentHealth(name, "error", Some(Json.fromString(err)))
      }
