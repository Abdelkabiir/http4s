package org.http4s
package server
package middleware

import cats.data.OptionT
import cats.effect._
import cats.implicits._
import fs2._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration}

object Timeout {

  @deprecated("Exists to support deprecated methods", "0.18.4")
  private def race[F[_]: Effect](timeoutResponse: F[Response[F]])(service: HttpRoutes[F])(
      implicit executionContext: ExecutionContext): HttpRoutes[F] =
    service.mapF { resp =>
      OptionT(fs2AsyncRace(resp.value, timeoutResponse.map(_.some)).map(_.merge))
    }

  @deprecated("Exists to support deprecated methods", "0.18.4")
  private def fs2AsyncRace[F[_], A, B](fa: F[A], fb: F[B])(
      implicit F: Effect[F],
      ec: ExecutionContext): F[Either[A, B]] =
    async.promise[F, Either[Throwable, Either[A, B]]].flatMap { p =>
      def go: F[Unit] = F.delay {
        val refToP = new AtomicReference(p)
        val won = new AtomicBoolean(false)
        val win = (res: Either[Throwable, Either[A, B]]) => {
          // important for GC: we don't reference the promise directly, and the
          // winner destroys any references behind it!
          if (won.compareAndSet(false, true)) {
            val action = refToP.getAndSet(null).complete(res)
            async.unsafeRunAsync(action)(_ => IO.unit)
          }
        }

        async.unsafeRunAsync(fa.map(Left.apply))(res => IO(win(res)))
        async.unsafeRunAsync(fb.map(Right.apply))(res => IO(win(res)))
      }

      go *> p.get.flatMap(F.fromEither)
    }

  /** Transform the service to return a timeout response after the given
    * duration if the service has not yet responded.  If the timeout
    * fires, the service's response continues to run in the background
    * and is discarded.
    *
    * @param timeout Finite duration to wait before returning `response`
    * @param routes [[HttpRoutes]] to transform
    */
  @deprecated(
    "Use apply(FiniteDuration, F[Response[F]](HttpRoutes[F]) instead. That cancels the losing effect.",
    "0.18.4")
  def apply[F[_]: Effect](timeout: Duration, response: F[Response[F]])(
      @deprecatedName('service) routes: HttpRoutes[F])(
      implicit executionContext: ExecutionContext,
      scheduler: Scheduler): HttpRoutes[F] =
    timeout match {
      case fd: FiniteDuration => race(scheduler.effect.delay(response, fd))(routes)
      case _ => routes
    }

  /** Transform the service to return a timeout response after the given
    * duration if the service has not yet responded.  If the timeout
    * fires, the service's response continues to run in the background
    * and is discarded.
    *
    * @param timeout Finite duration to wait before returning `response`
    */
  @deprecated(
    "Use apply(FiniteDuration)(HttpRoutes[F]) instead. That cancels the losing effect.",
    "0.18.4")
  def apply[F[_]: Effect](timeout: Duration)(@deprecatedName('service) routes: HttpRoutes[F])(
      implicit executionContext: ExecutionContext,
      scheduler: Scheduler): HttpRoutes[F] =
    apply(timeout, Response[F](Status.InternalServerError).withBody("The service timed out."))(
      routes)

  /** Transform the service to return a timeout response after the given
    * duration if the service has not yet responded.  If the timeout
    * fires, the service's response is canceled.
    *
    * @param timeout Finite duration to wait before returning a `500
    * Internal Server Error` response
    * @param service [[HttpRoutes]] to transform
    */
  def apply[F[_]](timeout: FiniteDuration, timeoutResponse: F[Response[F]])(
      @deprecatedName('service) routes: HttpRoutes[F])(
      implicit F: Concurrent[F],
      T: Timer[F]): HttpRoutes[F] = {
    val OTC = Concurrent[OptionT[F, ?]]
    routes
      .mapF(respF => OTC.race(respF, OptionT.liftF(T.sleep(timeout) *> timeoutResponse)))
      .map(_.merge)
  }

  /** Transform the service to return a timeout response after the given
    * duration if the service has not yet responded.  If the timeout
    * fires, the service's response is canceled.
    *
    * @param timeout Finite duration to wait before returning a `500
    * Internal Server Error` response
    * @param service [[HttpRoutes]] to transform
    */
  def apply[F[_]](timeout: FiniteDuration)(@deprecatedName('service) routes: HttpRoutes[F])(
      implicit F: Concurrent[F],
      T: Timer[F]): HttpRoutes[F] =
    apply(
      timeout,
      Response[F](Status.InternalServerError).withEntity("The response timed out.").pure[F])(routes)
}
