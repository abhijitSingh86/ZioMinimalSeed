import utils.network.CircuitBreaker.{CircuitBreakerHalfOpen, CircuitBreakerOpened}
import utils.network.Domain._
import utils.network.{CircuitBreaker, Domain, LoadBalancer, RetryUtil}
import zio.duration._
import zio.test.Assertion._
import zio.test.environment.TestClock
import zio.test.{testM, _}
import zio.{IO, _}

import scala.collection.mutable.ArrayBuffer
object DomainSpec extends DefaultRunnableSpec {
  def retrySpec = suite("Test withRetries Function")(
    testM("successful run, no retry needed scenario") {
      val call = (ref: Ref[Int]) => {
        ref.update(_ + 1)
        IO.succeed(SuccessResponse(200, "Success"))
      }
      for {
        ref    <- Ref.make[Int](0)
        _      <- RetryUtil.withRetries(call(ref), 1)
        output <- ref.get
      } yield assert(output)(equalTo(0))
    },
    testM("successful run, after 1 retry needed scenario") {
      val call: Ref[Int] => IO[HttpClientError, Response] = (ref: Ref[Int]) => {
        ref.updateAndGet(_ + 1).flatMap {
          case x if x < 1 => IO.fail(ConnectionError)
          case _          => IO.succeed(SuccessResponse(200, "Success"))
        }
      }
      for {
        ref    <- Ref.make[Int](0)
        _      <- RetryUtil.withRetries(call(ref), 1)
        output <- ref.get
      } yield assert(output)(equalTo(1))
    },
    testM("Fail in case of max retries reached") {
      val call: Ref[Int] => IO[HttpClientError, Response] = (ref: Ref[Int]) => {
        ref.updateAndGet(_ + 1).flatMap {
          case _ => IO.fail(ConnectionError)
        }
      }
      for {
        ref      <- Ref.make[Int](0)
        response <- RetryUtil.withRetries(call(ref), 1).flip
        refCount <- ref.get
      } yield assert(response)(isSubtype[HttpClientError](anything)) && assert(refCount)(equalTo(2))
    },
    testM("Retry in case on Connection Error") {
      val call: Ref[Int] => IO[HttpClientError, Response] = (ref: Ref[Int]) => {
        ref.updateAndGet(_ + 1).flatMap {
          case x if x < 1 => IO.fail(ConnectionError)
          case _          => IO.succeed(SuccessResponse(200, "done"))
        }
      }
      for {
        ref      <- Ref.make[Int](0)
        _        <- RetryUtil.withRetries(call(ref), 1)
        refCount <- ref.get
      } yield assert(refCount)(equalTo(1))
    },
    testM("Fail when maxRetry in case on Connection Error") {
      val call: Ref[Int] => IO[HttpClientError, Response] = (ref: Ref[Int]) => {
        ref.updateAndGet(_ + 1).flatMap {
          case _ => IO.fail(ConnectionError)
        }
      }
      for {
        ref      <- Ref.make[Int](0)
        response <- RetryUtil.withRetries(call(ref), 2).flip
        refCount <- ref.get
      } yield assert(refCount)(equalTo(3)) && assert(response)(isSubtype[HttpClientError](anything))
    },
    testM("Retry in case on failure with code>500") {
      val successResponse = SuccessResponse(200, "Done")
      val call: Ref[Int] => IO[HttpClientError, Response] = (ref: Ref[Int]) => {
        ref.getAndUpdate(_ + 1).flatMap {
          case x if x <= 1 => IO.fail(ConnectionError)
          case x if x <= 3 => IO.succeed(FailureResponse(500, "Fail"))
          case _           => IO.succeed(successResponse)
        }
      }
      for {
        ref      <- Ref.make[Int](0)
        response <- RetryUtil.withRetries(call(ref), 4)
        refCount <- ref.get
      } yield assert(refCount)(equalTo(5)) && assert(response)(equalTo(successResponse))
    }
  )
  def loadBalancerSpec = suite("Test withLoadBalancer Function")(
    testM("Return connection error when no server is present") {
      val dummyResponse = SuccessResponse(200, "Done")
      val dummyRequest  = Request("something")
      def call: Request => Server => IO[HttpClientError, Response] =
        (_: Request) =>
          (_: Server) => {
            IO.succeed(dummyResponse)
          }
      val response = for {
        server <- LoadBalancer.withLoadBalancer(List.empty[Server], call)
        r = server(dummyRequest).flip
      } yield r

      response.flatMap { r =>
        assertM(r)(isSubtype[HttpClientError](anything))
      }
    },
    testM("Runs on first server in single request") {
      val dummyResponse = SuccessResponse(200, "Done")
      val dummyRequest  = Request("something")
      val serverBuffer  = new ArrayBuffer[Server]()
      def call: Request => Server => IO[HttpClientError, Response] =
        (_: Request) =>
          (server: Server) => {
            serverBuffer.append(server)
            IO.succeed(dummyResponse)
          }
      val servers = List(Server("h0", 0), Server("h1", 1))
      val response: ZIO[Any, HttpClientError, Response] =
        LoadBalancer.withLoadBalancer(servers, call).flatMap(x => x(dummyRequest))

      for {
        r <- response
      } yield assert(r)(equalTo(dummyResponse)) && assert(serverBuffer.head)(equalTo(servers.head))

    },
    testM("Runs in round robin fashion in multiple requests") {
      val dummyResponse = SuccessResponse(200, "Done")
      val dummyRequest  = Request("something")
      val serverBuffer  = new ArrayBuffer[Server]()
      def call: Request => Server => IO[HttpClientError, Response] =
        (_: Request) =>
          (server: Server) => {
            serverBuffer.append(server)
            IO.succeed(dummyResponse)
          }
      val servers = List[Server](Server("h0", 0), Server("h1", 1))
      val responses: ZIO[Any, Nothing, List[IO[HttpClientError, Response]]] = for {
        server <- LoadBalancer.withLoadBalancer(servers, call)
        r0 = server(dummyRequest)
        r1 = server(dummyRequest)
        r2 = server(dummyRequest)
        r3 = server(dummyRequest)
        r4 = server(dummyRequest)
      } yield List(r0, r1, r2, r3, r4)

      val resultingServerCallSequence: Seq[Server] = servers ::: (servers :+ servers.head)
      responses.flatMap { x =>
        for {
          res <- IO.collectAllPar(x)
        } yield assert(serverBuffer.toList)(equalTo(resultingServerCallSequence)) && assert(res)(
          equalTo(Array.fill(5)(dummyResponse).toList)
        )
      }
    }
  )

  def circuitBreakerSpec = suite("Test Circuit Breaker function ")(
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
        failedResponse     = otherHalfOpenResponse.fold(_ == CircuitBreakerHalfOpen, _ => false)
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

  def spec = suite("All Tests")(circuitBreakerSpec)//, loadBalancerSpec, retrySpec)
}
