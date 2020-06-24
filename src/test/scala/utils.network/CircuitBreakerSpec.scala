package utils.network

import utils.network.CircuitBreaker.CircuitBreakerOpened
import utils.network.Domain.{ConnectionError, Request, Server, SuccessResponse}
import zio.{IO, ZIO}
import zio.test.Assertion.equalTo
import zio.test.environment.TestClock
import zio.test.{DefaultRunnableSpec, assert, suite, testM}
import zio.duration._

object CircuitBreakerSpec  extends DefaultRunnableSpec {

  def spec = suite("Test Circuit Breaker function ")(
    testM("Successfull request with no error") {
      val request  = Request("data")
      val server   = Server("host", 9999)
      val response = SuccessResponse(200, "success")
      val call = (_: Request) =>
        (_: Server) => {
          IO.effectTotal(response)
        }

      val result = CircuitBreaker.withCircuitBreaker(call).flatMap { cb =>
        cb(request)(server)
      }

      for {
        r <- result
      } yield assert(r)(equalTo(response))

    },
    testM("Circuit open after maxFailure reached") {
      val request = Request("data")
      val server  = Server("host", 9999)
      val call = (_: Request) =>
        (_: Server) => {
          IO.fail(ConnectionError)
        }

      for {
        cb   <- CircuitBreaker.withCircuitBreaker(call, maxFailures = 2)
        _    <- cb(request)(server).flip
        _    <- cb(request)(server).flip
        res3 <- cb(request)(server).flip
        exc = res3.fold(_ == CircuitBreakerOpened, _ => false)
      } yield assert(res3.isLeft)(equalTo(true)) && assert(exc)(equalTo(true))
    },
    testM("Open circuit switches to half open and then again to closes after half open success") {
      val request         = Request("data")
      val server          = Server("host", 9999)
      val successResponse = SuccessResponse(200, "done")
      var counter         = 0
      val call: Request => Server => ZIO[Any, Domain.ConnectionError.type, SuccessResponse] = (_: Request) =>
        (_: Server) => {
          counter = counter + 1
          if (counter == 3) {  // just after the open state switches to half open and first request passes by.
            Thread.sleep(3000) // need to introduce the delay so that half open stays for a while
            ZIO.succeed(successResponse)
          } else {
            IO.fail(ConnectionError)
          }
        }

      for {
        cb   <- CircuitBreaker.withCircuitBreaker(call, maxFailures = 2)
        _    <- cb(request)(server).flip
        _    <- cb(request)(server).flip
        _    <- cb(request)(server).flip
        res4 <- cb(request)(server).flip
        exc = res4.fold(_ == CircuitBreakerOpened, _ => false)
        _                           <- TestClock.adjust(16.seconds)
        firstHalfOpenResponseForked <- cb(request)(server).fork
        otherHalfOpenResponse       <- cb(request)(server).flip
        firstHalfOpenResponse       <- firstHalfOpenResponseForked.join
        nextConnectionFailResponse  <- cb(request)(server).flip
        nextResponseStatus = nextConnectionFailResponse.fold(_ => false, _ == ConnectionError)
        failedResponse     = otherHalfOpenResponse.fold(_ == CircuitBreakerOpened, _ => false)
      } yield assert(exc)(equalTo(true)) &&
        assert(failedResponse)(equalTo(true)) &&
        assert(firstHalfOpenResponse)(equalTo(successResponse)) &&
        assert(nextResponseStatus)(equalTo(true)) &&
        assert(counter)(equalTo(4)) // 2 for initial failure +1 for half request +1 for request after success from half open
    }
    //    testM("Open circuit switches to half open and then opens again after error response") {
    //      val request = Request("data")
    //      val server  = Server("host", 9999)
    //      var counter = 0
    //      val call: Request => Server => ZIO[Any, TestAssignment.ConnectionError.type, SuccessResponse] = (_: Request) =>
    //        (_: Server) => {
    //          counter = counter + 1
    //          IO.fail(ConnectionError)
    //        }
    //
    //      for {
    //        cb   <- CircuitBreaker.withCircuitBreaker(call, maxFailures = 2)
    //        _    <- cb(request)(server).flip
    //        _    <- cb(request)(server).flip
    //        _    <- cb(request)(server).flip
    //        res4 <- cb(request)(server).flip
    //        exc = res4.fold(_ == CircuitBreakerOpened, _ => false)
    //        _                                <- TestClock.adjust(16.seconds)
    //        firstHalfOpenResponseForked      <- cb(request)(server).flip.fork
    //        otherHalfOpenResponse      <- cb(request)(server).flip
    //        _                                <- firstHalfOpenResponseForked.join
    //        nextConnectionFailResponseForked <- cb(request)(server).flip.fork
    //        nextConnectionFailResponse       <- nextConnectionFailResponseForked.join
    //        nextResponseStatus = nextConnectionFailResponse.fold(_ == CircuitBreakerOpened, _ => false)
    //        failedResponse     = otherHalfOpenResponse.fold(_ == CircuitBreakerHalfOpen, _ => false)
    //      } yield assert(exc)(equalTo(true)) &&
    //        assert(failedResponse)(equalTo(true)) &&
    //        assert(nextResponseStatus)(equalTo(true)) &&
    //        assert(counter)(equalTo(3)) // 2 for initial failure +1 for half request
    //    }@@ eventually
    //      @@ timeout(5.seconds),
    //    testM("Circuit switches from closed to open and make half open after time lapse") {
    //      val request = Request("data")
    //      val server  = Server("host", 9999)
    //      val call: Ref[Int] => Request => Server => ZIO[Any, TestAssignment.ConnectionError.type, Response] =
    //        (ref: Ref[Int]) => {
    //          (_: Request) =>
    //            (_: Server) => {
    //              ref.update(x => x + 1).flatMap { _ =>
    //                IO.fail(ConnectionError)
    //              }
    //            }
    //        }
    //
    //      for {
    //        ref      <- Ref.make[Int](0)
    //        cb       <- CircuitBreaker.withCircuitBreaker(call(ref), maxFailures = 2)
    //        requestsForked <- ZIO.collectAllPar((0 to 10).map(_ => cb(request)(server).flip)).fork
    //        _ <- TestClock.adjust(16.seconds)
    //        _ <- requestsForked.join
    //        flag <- ref.get.map(n => n == 3)
    //      } yield assert(flag)(equalTo(true))
    //    }@@ eventually
    //      @@ timeout(10.seconds)
  )
}
