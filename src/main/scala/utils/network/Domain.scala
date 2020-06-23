package utils.network

object Domain {
  sealed case class Server(host: String, port: Int)
  sealed case class Request(body: String)
  sealed trait Response
  case class SuccessResponse(code: Int, body: String) extends Response
  case class FailureResponse(code: Int, body: String) extends Response

  sealed trait HttpClientError
  case object ConnectionError extends HttpClientError
  case object TimeoutError    extends HttpClientError
}
