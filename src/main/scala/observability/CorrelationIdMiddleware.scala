package observability

import java.util.UUID

import org.http4s.*
import org.typelevel.ci.CIString

import cats.data.{Kleisli, OptionT}
import cats.effect.*
import cats.syntax.all.*

object CorrelationIdMiddleware:

  val RequestIdHeader: CIString = CIString("X-Request-Id")

  /** Simpler signature: takes the IOLocal and returns a middleware function. */
  def middleware(
      local: IOLocal[Option[String]],
  )(routes: HttpRoutes[IO]): HttpRoutes[IO] = Kleisli { (req: Request[IO]) =>
    val correlationId = req.headers.get(RequestIdHeader).map(_.head.value)
      .getOrElse(UUID.randomUUID().toString)

    OptionT(
      local.set(Some(correlationId)) *> routes(req)
        .map(resp => resp.putHeaders(Header.Raw(RequestIdHeader, correlationId)))
        .value.guarantee(local.set(None)),
    )
  }

  /** Build an IOLocal[Option[String]] for the correlation ID. Create once in
    * Main, pass to middleware and anywhere that needs it.
    */
  def makeLocal: IO[IOLocal[Option[String]]] = IOLocal(Option.empty[String])

  /** Read the current request's correlation ID inside an IO context. */
  def currentId(local: IOLocal[Option[String]]): IO[String] = local.get
    .map(_.getOrElse("no-request-id"))
