package utils.network

import java.time.Instant

import CircuitBreaker.{ CircuitBreakerOpened }
import Domain._
import utils.network.Domain.{ ConnectionError, HttpClientError, Request, Response, Server }
import zio._
import zio.clock.Clock
import zio.duration._

trait CircuitBreaker {
  def withCircuitBreaker(
    call: Request => Server => IO[HttpClientError, Response],
    maxFailures: Int = 5,
    remainOpened: Duration = 15.seconds
  ): URIO[Clock, Request => Server => IO[Either[CircuitBreakerOpened, HttpClientError], Response]]
}

object CircuitBreaker {
  sealed trait CircuitBreakerOpened
  case object CircuitBreakerOpened extends CircuitBreakerOpened

  case class CircuitState(
                           status: Option[CircuitBreakerOpened],
                           continuesFailedCount: Int = 0,
                           openStateEndTime: Option[Instant] = None,
                           halfOpenRequestCount: Int = 0
  ) {
    def resetState(): CircuitState =
      this.copy(status = None, continuesFailedCount = 0, openStateEndTime = None, halfOpenRequestCount = 0)

    def setToOpenState(endInstant: Instant): CircuitState =
      this.copy(status = Some(CircuitBreakerOpened), openStateEndTime = Some(endInstant), halfOpenRequestCount = 0)

    def incrementFail(currentInstant: Instant, maxFailCount: Int, remainOpen: Duration): CircuitState = {
      this.copy(continuesFailedCount = this.continuesFailedCount + 1)
        .updateOpenState(currentInstant,maxFailCount,remainOpen)
    }
    def updateOpenState(currentInstant: Instant, maxFailCount: Int, remainOpen: Duration): CircuitState =
      if(this.halfOpenRequestCount > 0){
        this.copy(halfOpenRequestCount = this.halfOpenRequestCount+1)
      }else
      this.status match {
        case Some(_) =>
          if (openStateEndTime.isDefined && currentInstant.isAfter(openStateEndTime.get)) {
            this.copy(status = None, openStateEndTime = None, halfOpenRequestCount = halfOpenRequestCount + 1)
          } else
            this
        case None =>
          if (continuesFailedCount >= maxFailCount) {
            setToOpenState(currentInstant.plusNanos(remainOpen.toNanos))
          } else this
      }
    def updateHalfOpenRequests(): CircuitState =
      this.copy(halfOpenRequestCount = this.halfOpenRequestCount + 1)
  }

  def withCircuitBreaker(
    call: Request => Server => IO[HttpClientError, Response],
    maxFailures: Int = 5,
    remainOpened: Duration = 15.seconds
  ): URIO[Clock, Request => Server => IO[Either[CircuitBreakerOpened, HttpClientError], Response]] =
    Ref.make[CircuitState](CircuitState(None)).flatMap { ref =>
      for {
        clock <- ZIO.access[Clock](_.get)
      } yield (
        (
          (r: Request) =>
            (s: Server) => {
              clock.currentDateTime.orDie.flatMap { cdt =>
                ref
                  .updateAndGet(_.updateOpenState(cdt.toInstant, maxFailures, remainOpened))
                  .flatMap(_ match {
                    case state if state.halfOpenRequestCount == 1 =>
                      call(r)(s)
                        .mapError(Right(_))
                        .either
                        .flatMap {
                          case _ @ Left(Right(e)) if e == ConnectionError =>
                            ref
                              .update(_.setToOpenState(cdt.toInstant))
                              .map(_ => Left(Left(CircuitBreakerOpened)))
                          case response => ref.update(_.resetState()).map(_ => response)
                        }
                        .absolve
                      // Is OPen state is present or HalfOpen Requests are greater than one
                    case state if state.status.isDefined || state.halfOpenRequestCount > 1 => ZIO.fail(Left(CircuitBreakerOpened))

                    case state if state.status.isEmpty && state.halfOpenRequestCount == 0 =>
                      val ee: URIO[Any, Either[Either[Nothing, HttpClientError], Response]] = call(r)(s).mapError(Right(_)).either

                      def isConnectionError(e:Either[Nothing,HttpClientError]):Boolean = e.map(_ == ConnectionError).getOrElse(false)

                      ee.flatMap {
                        case _ @ Left(e) if isConnectionError(e) =>
                          ref.updateAndGet(_.incrementFail(cdt.toInstant, maxFailures, remainOpened)).map(_.status match{
                          case Some(_) => Left(Left(CircuitBreakerOpened))
                          case _ => Left(e)
                        })
                        case _ @ Left(e)                         => ref.update(_.resetState()).map(_ =>Left(e))
                        case response @ Right(_)                 => ref.update(_.resetState()).map(_ => response)
                      }.absolve
                  })
              }
            }
          )
        )
    }

}
