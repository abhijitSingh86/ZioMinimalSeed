package utils.network

import utils.network.Domain.{ConnectionError, FailureResponse, HttpClientError, Response}
import zio.{IO, Ref}

object RetryUtil {
  def withRetries(call: IO[HttpClientError, Response], maxRetries: Int = 5): IO[HttpClientError, Response] =
    Ref.make[Int](0).flatMap { ref =>
      def retryAgain(lastError: Option[Either[HttpClientError, Response]]): IO[HttpClientError, Response] =
        ref.get.flatMap { iteration =>
          if (iteration <= maxRetries) {
            call foldM ((e: HttpClientError) =>
              e match {
                case ConnectionError => ref.update(_ + 1) *> retryAgain(Some(Left(e)))
                case x               => IO.fail(x)
              }, (res: Response) =>
              res match {
                case x: FailureResponse if x.code >= 500 => ref.update(_ + 1) *> retryAgain(Some(Right(x)))
                case x                                   => IO.succeed(x)
              })
          } else {
            lastError.map(x => x.fold(IO.fail(_), IO.succeed(_))).getOrElse(IO.dieMessage("Last error at this point can't be empty"))
          }
        }
      retryAgain(None)
    }
}
