package utils.network

import utils.network.Domain.{HttpClientError, Request, Response, Server, SuccessResponse}
import zio.{IO, ZIO}
import zio.test.Assertion.{anything, equalTo, isSubtype}
import zio.test.{DefaultRunnableSpec, assert, assertM, suite, testM}

import scala.collection.mutable.ArrayBuffer

object LoadBalancerSpec  extends DefaultRunnableSpec {
  def spec =suite("Test withLoadBalancer Function")(
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
}
