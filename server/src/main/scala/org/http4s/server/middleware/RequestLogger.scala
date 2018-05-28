package org.http4s
package server
package middleware

import cats.data.{Kleisli, OptionT}
import cats.effect._
import cats.implicits._
import fs2._
import org.http4s.util.CaseInsensitiveString
import org.log4s._
import scala.concurrent.ExecutionContext

/**
  * Simple Middleware for Logging Requests As They Are Processed
  */
object RequestLogger {
  private[this] val logger = getLogger

  def apply[F[_]: Effect](
      logHeaders: Boolean,
      logBody: Boolean,
      redactHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains
  )(@deprecatedName('service) routes: HttpRoutes[F])(
      implicit ec: ExecutionContext = ExecutionContext.global): HttpRoutes[F] =
    Kleisli { req =>
      if (!logBody)
        OptionT(Logger
          .logMessage[F, Request[F]](req)(logHeaders, logBody)(logger.info(_)) *> routes(req).value)
      else
        OptionT
          .liftF(async.refOf[F, Vector[Segment[Byte, Unit]]](Vector.empty[Segment[Byte, Unit]]))
          .flatMap { vec =>
            val newBody = Stream
              .eval(vec.get)
              .flatMap(v => Stream.emits(v).covary[F])
              .flatMap(c => Stream.segment(c).covary[F])

            val changedRequest = req.withBodyStream(
              req.body
              // Cannot Be Done Asynchronously - Otherwise All Chunks May Not Be Appended Previous to Finalization
                .observe(_.segments.flatMap(s => Stream.eval_(vec.modify(_ :+ s))))
            )

            val response = routes(changedRequest)
            response.attempt
              .flatMap {
                case Left(e) =>
                  OptionT.liftF(
                    Logger.logMessage[F, Request[F]](req.withBodyStream(newBody))(
                      logHeaders,
                      logBody,
                      redactHeadersWhen)(logger.info(_)) *>
                      Sync[F].raiseError[Response[F]](e)
                  )
                case Right(resp) =>
                  Sync[OptionT[F, ?]].pure(
                    resp.withBodyStream(
                      resp.body.onFinalize(
                        Logger.logMessage[F, Request[F]](req.withBodyStream(newBody))(
                          logHeaders,
                          logBody,
                          redactHeadersWhen)(logger.info(_))
                      )
                    )
                  )
              }
              .orElse(
                OptionT(
                  Logger
                    .logMessage[F, Request[F]](req.withBodyStream(newBody))(
                      logHeaders,
                      logBody,
                      redactHeadersWhen
                    )(logger.info(_))
                    .as(Option.empty[Response[F]])
                )
              )
          }
    }
}
