package fr.davit.akka.http.metrics.dropwizard.marshalling

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import fr.davit.akka.http.metrics.core.HttpMetricsRegistry.StatusGroupDimension
import fr.davit.akka.http.metrics.core.scaladsl.server.HttpMetricsDirectives._
import fr.davit.akka.http.metrics.dropwizard.DropwizardRegistry
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import spray.json.{DefaultJsonProtocol, JsValue}

import scala.concurrent.duration._

class DropwizardMarshallersSpec extends FlatSpec with Matchers with ScalatestRouteTest with BeforeAndAfterAll {

  final case class JsonResponse(metrics: Map[String, JsValue])

  trait Fixture extends SprayJsonSupport with DefaultJsonProtocol with DropwizardMarshallers {
    implicit val metricsFormat = jsonFormat1(JsonResponse)

    val registry = DropwizardRegistry()
    registry.underlying.counter("other.metric")
  }

  override def afterAll(): Unit = {
    cleanUp()
    super.afterAll()
  }

  "DropwizardMarshallers" should "expose metrics as json format" in new Fixture {
    // use metrics so they appear in the report
    val dimensions = Seq(StatusGroupDimension(StatusCodes.OK))
    registry.requests.inc()
    registry.receivedBytes.update(10)
    registry.active.inc()
    registry.responses.inc(dimensions)
    registry.errors.inc()
    registry.duration.observe(1.second, dimensions)
    registry.sentBytes.update(10)

    Get() ~> metrics(registry) ~> check {
      val json = responseAs[JsonResponse]
      // println(json)
      json.metrics.keys should contain theSameElementsAs Seq(
        "akka.http.requests.active",
        "akka.http.requests",
        "akka.http.requests.bytes",
        "akka.http.responses{status=2xx}",
        "akka.http.responses.errors",
        "akka.http.responses.duration{status=2xx}",
        "akka.http.responses.bytes",
        "other.metric"
      )
    }
  }

}
