package utils.network

import utils.network.Domain.{ConnectionError, HttpClientError, Request, Response, Server}
import zio.{IO, Ref, UIO}

object LoadBalancer {

  def withLoadBalancer(
                        servers: List[Server],
                        call: Request => Server => IO[HttpClientError, Response]
                      ): UIO[Request => IO[HttpClientError, Response]] = Ref.make[Int](0).flatMap { ref =>
    UIO { (r: Request) =>
      if (servers.size == 0) {
        IO.fail(ConnectionError)
      } else
        ref.getAndUpdate(c => (c + 1) % servers.size).flatMap { next =>
          call(r)(servers(next))
        }
    }
  }
}
