package utils.network

import utils.network.Domain.{ConnectionError, FailureResponse, HttpClientError, Response, SuccessResponse}
import zio.{IO, Ref}
import zio.test.Assertion.{anything, equalTo, isSubtype}
import zio.test.{DefaultRunnableSpec, assert, suite, testM}

object RetryUtilSpec extends DefaultRunnableSpec {
  def spec = suite("Test withRetries Function")(
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

}
