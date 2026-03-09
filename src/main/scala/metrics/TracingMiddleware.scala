package metrics

import cats.data.{Kleisli, OptionT}
import cats.effect.*
import cats.syntax.all.*
import org.http4s.*
import org.typelevel.otel4s.trace.{Tracer, SpanKind, StatusCode}

/** Lightweight HTTP tracing middleware using otel4s.
  *
  * Creates a root span per request with HTTP method, path, and status code
  * attributes. Child spans for checkAndConsume / executeIdempotent are created
  * inside the respective service implementations using the Tracer in scope.
  */
object TracingMiddleware:

  def apply[F[_]: Async: Tracer](routes: HttpRoutes[F]): HttpRoutes[F] =
    Kleisli { (req: Request[F]) =>
      val spanName = s"${req.method.name} ${req.pathInfo.renderString}"
      val tracer = Tracer[F]

      OptionT.liftF(
        tracer.spanBuilder(spanName)
          .addAttribute(org.typelevel.otel4s.Attribute("http.method", req.method.name))
          .addAttribute(org.typelevel.otel4s.Attribute("http.target", req.pathInfo.renderString))
          .withSpanKind(SpanKind.Server)
          .build
          .use { span =>
            routes(req).semiflatMap { resp =>
              span.addAttribute(
                org.typelevel.otel4s.Attribute("http.status_code", resp.status.code.toLong),
              ) *>
              (if resp.status.isSuccess then Async[F].unit
               else span.setStatus(StatusCode.Error)) *>
              Async[F].pure(resp)
            }.getOrElseF(
              span.setStatus(StatusCode.Error) *>
              Async[F].pure(Response[F](Status.NotFound)),
            )
          }
      )
    }

  /** Create a child span for a named operation. Use inside service methods:
    *
    * {{{
    * TracingMiddleware.traced("checkAndConsume") {
    *   store.checkAndConsume(key, cost, profile)
    * }
    * }}}
    */
  def traced[F[_]: Tracer: Async, A](name: String)(fa: F[A]): F[A] =
    Tracer[F].span(name).use(_ => fa)