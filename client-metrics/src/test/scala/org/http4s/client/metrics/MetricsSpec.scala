package org.http4s.client.metrics

import cats.effect.IO
import com.codahale.metrics.{MetricRegistry, SharedMetricRegistries}
import java.net.ServerSocket
import org.http4s.{Http4sSpec, HttpRoutes, Status, Uri}
import org.http4s.client.UnexpectedStatus
import org.http4s.client.blaze.Http1Client
import org.http4s.dsl.io._
import org.http4s.server.blaze.BlazeBuilder
import org.http4s.server.middleware.AutoSlash
import org.specs2.specification.AfterAll
import scala.util.{Failure, Success, Try}

import scala.concurrent.ExecutionContext.Implicits.global

class MetricsSpec extends Http4sSpec with AfterAll {

  val clientRemoteStub = new RemoteEndpointStub()

  override def afterAll(): Unit =
    clientRemoteStub.shutdown()

  val httpClient = Http1Client[IO]().unsafeRunSync

  "A http client with a metrics middleware" should {
    "register a successful 2xx response" in {
      val registry: MetricRegistry = SharedMetricRegistries.getOrCreate("test1")
      val serviceClient = Metrics(registry, "client")(httpClient)

      val resp = serviceClient.expect[String](clientRemoteStub.url / "ok").attempt.unsafeRunSync()

      resp must beRight { contain("200 OK") }
      val count = registry.getCounters.get("client.default.2xx-responses").getCount
      count must beEqualTo(1)
    }

    "register a failed 4xx response" in {
      val registry: MetricRegistry = SharedMetricRegistries.getOrCreate("test2")
      val serviceClient = Metrics(registry, "client")(httpClient)

      val resp =
        serviceClient.expect[String](clientRemoteStub.url / "badrequest").attempt.unsafeRunSync()

      resp must beLeft { (e: Throwable) =>
        e must beLike { case UnexpectedStatus(Status(400)) => ok }
      }
      val count = registry.getCounters.get("client.default.4xx-responses").getCount
      count must beEqualTo(1)
    }

    "register a failed 5xx response" in {
      val registry: MetricRegistry = SharedMetricRegistries.getOrCreate("test3")
      val serviceClient = Metrics(registry, "client")(httpClient)

      val resp =
        serviceClient.expect[String](clientRemoteStub.url / "error").attempt.unsafeRunSync()

      resp must beLeft { (e: Throwable) =>
        e must beLike { case UnexpectedStatus(Status(500)) => ok }
      }
      val count = registry.getCounters.get("client.default.5xx-responses").getCount
      count must beEqualTo(1)
    }

  }
}

class RemoteEndpointStub {
  private val port = FreePort.find
  private val host = "localhost"
  val url = Uri.unsafeFromString(s"http://$host:$port")

  private val mockEndpoints = HttpRoutes.of[IO] {
    case GET -> Root / "badrequest" =>
      BadRequest("400 Bad Request")
    case GET -> Root / "error" =>
      InternalServerError("500 Internal Server Error")
    case GET -> Root / "ok" =>
      Ok("200 OK")
  }
  private val serviceStubBuilder =
    BlazeBuilder[IO].bindHttp(port, host).mountService(AutoSlash(mockEndpoints), "/").start
  private val serviceStub = serviceStubBuilder.unsafeRunSync()

  def shutdown(): Unit =
    serviceStub.shutdownNow()
}

object FreePort {
  def find: Int =
    tryWithResources(new ServerSocket(0)) { socket =>
      socket.setReuseAddress(true)
      socket.getLocalPort
    }

  def tryWithResources[A <: AutoCloseable, B](resource: A)(block: A => B): B =
    Try(block(resource)) match {
      case Success(result) =>
        resource.close()
        result
      case Failure(e) =>
        resource.close()
        throw e
    }

}
