package utils.network

import java.time.Instant

import CircuitBreaker.CircuitBreakerState
import Domain._
import utils.network.Domain.{ConnectionError, HttpClientError, Request, Response, Server}
import zio._
import zio.clock.Clock
import zio.duration._

trait CircuitBreaker {

  def withCircuitBreaker(
    call: Request => Server => IO[HttpClientError, Response],
    maxFailures: Int = 5,
    remainOpened: Duration = 15.seconds
  ): URIO[Clock, Request => Server => IO[Either[CircuitBreakerState, HttpClientError], Response]]
}

object CircuitBreaker {
  sealed trait CircuitBreakerState
  case object CircuitBreakerOpened   extends CircuitBreakerState
  case object CircuitBreakerClosed   extends CircuitBreakerState
  case object CircuitBreakerHalfOpen extends CircuitBreakerState

  case class CircuitState(
    state: CircuitBreakerState,
    continuesFailedCount: Int = 0,
    openStateEndTime: Option[Instant] = None,
    halfOpenRequests: Int = 0
  ) {
    def resetState(): CircuitState =
      this.copy(state = CircuitBreakerClosed, continuesFailedCount = 0, openStateEndTime = None, halfOpenRequests = 0)

    def setToOpenState(endInstant: Instant): CircuitState =
      this.copy(state = CircuitBreakerOpened, openStateEndTime = Some(endInstant), halfOpenRequests = 0)

    def incrementFail(): CircuitState =
      this.copy(continuesFailedCount = this.continuesFailedCount + 1)
    def updateOpenState(currentInstant: Instant, maxFailCount: Int, remainOpen: Duration): CircuitState =
      this.state match {
        case CircuitBreakerOpened =>
          if (openStateEndTime.isDefined && currentInstant.isAfter(openStateEndTime.get)) {
            this.copy(state = CircuitBreakerHalfOpen, openStateEndTime = None, halfOpenRequests = 0)
          } else
            this
        case CircuitBreakerClosed =>
          if (continuesFailedCount >= maxFailCount) {
            setToOpenState(currentInstant.plusNanos(remainOpen.toNanos))
          } else this
        case CircuitBreakerHalfOpen => this
      }
    def updateHalfOpenRequests(): CircuitState =
      this.copy(halfOpenRequests = this.halfOpenRequests + 1)
  }

  def withCircuitBreaker(
    call: Request => Server => IO[HttpClientError, Response],
    maxFailures: Int = 5,
    remainOpened: Duration = 15.seconds
  ): URIO[Clock, Request => Server => IO[Either[CircuitBreakerState, HttpClientError], Response]] =
    Ref.make[CircuitState](CircuitState(CircuitBreakerClosed)).flatMap { ref =>
      for {
        clock <- ZIO.access[Clock](_.get)
      } yield (
        (
          (r: Request) =>
            (s: Server) => {
              clock.currentDateTime.orDie.flatMap { cdt =>
                ref
                  .updateAndGet(_.updateOpenState(cdt.toInstant, maxFailures, remainOpened))
                  .flatMap(_.state match {
                    case CircuitBreakerOpened => ZIO.fail(Left(CircuitBreakerOpened))

                    case CircuitBreakerClosed =>
                      call(r)(s).either.flatMap {
                        case err @ Left(e) if e == ConnectionError => ref.update(_.incrementFail()).map(_ => err)
                        case err @ Left(_)                         => ZIO.succeed(err)
                        case response                              => ref.update(_.resetState()).map(_ => response)
                      }.absolve.mapError(Right(_))

                    case CircuitBreakerHalfOpen =>
                      ref
                        .updateAndGet(_.updateHalfOpenRequests())
                        .flatMap(_.halfOpenRequests match {
                          case 1 =>
                            call(r)(s)
                              .mapError(Right(_))
                              .either
                              .flatMap {
                                case err @ Left(Right(e)) =>
                                  if (e == ConnectionError)
                                    ref
                                      .update(_.setToOpenState(cdt.toInstant))
                                      .map(_ => Left(Left(CircuitBreakerOpened)))
                                  else
                                    ref.update(_.resetState()).map(_ =>err)
                                case response => ref.update(_.resetState()).map(_ => response)
                              }
                              .absolve
                          case _ => ZIO.fail(CircuitBreakerHalfOpen).mapError(Left(_))
                        })
                  })
              }
            }
          )
        )
    }

}
