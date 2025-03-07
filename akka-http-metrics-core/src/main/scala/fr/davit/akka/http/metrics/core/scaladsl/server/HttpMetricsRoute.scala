package fr.davit.akka.http.metrics.core.scaladsl.server

import akka.NotUsed
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route, RoutingLog}
import akka.http.scaladsl.settings.{ParserSettings, RoutingSettings}
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import fr.davit.akka.http.metrics.core.HttpMetricsRegistry
import fr.davit.akka.http.metrics.core.HttpMetricsRegistry.StatusGroupDimension

import scala.concurrent.duration.Deadline
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

object HttpMetricsRoute {

  implicit def apply(route: Route): HttpMetricsRoute = new HttpMetricsRoute(route)

}

/**
  * Typeclass to add the metrics capabilities to a route
  *
  */
class HttpMetricsRoute private (route: Route) extends HttpMetricsDirectives {

  private def metricsHandler(registry: HttpMetricsRegistry, settings: HttpMetricsSettings, handler: HttpRequest => Future[HttpResponse])(
      request: HttpRequest)(
      implicit
      executionContext: ExecutionContext
  ): Future[HttpResponse] = {
    registry.active.inc()
    registry.requests.inc()
    registry.receivedBytes.update(request.entity.contentLengthOption.getOrElse(0L))
    val start = Deadline.now
    val response = handler(request)
    // no need to handle failures at this point. They will fail the stream hence the server
    response.map { r =>
      registry.active.dec()
      val dimensions = Seq(StatusGroupDimension(r.status))
      registry.responses.inc(dimensions)
      registry.duration.observe(Deadline.now - start, dimensions)
      if (settings.defineError(r)) registry.errors.inc()
      r.entity.contentLengthOption.foreach(registry.sentBytes.update(_))
      r
    }
  }

  def recordMetrics(
      registry: HttpMetricsRegistry,
      settings: HttpMetricsSettings = HttpMetricsSettings.default)(
      implicit
      routingSettings: RoutingSettings,
      parserSettings: ParserSettings,
      materializer: Materializer,
      routingLog: RoutingLog,
      executionContext: ExecutionContextExecutor = null,
      rejectionHandler: RejectionHandler = RejectionHandler.default,
      exceptionHandler: ExceptionHandler = null): Flow[HttpRequest, HttpResponse, NotUsed] = {

    // override the execution context passed as parameter
    val effectiveEC = if (executionContext ne null) executionContext else materializer.executionContext

    {
      implicit val executionContext: ExecutionContextExecutor = effectiveEC
      Flow[HttpRequest]
        .mapAsync(1)(metricsHandler(registry, settings, Route.asyncHandler(route)))
        .watchTermination() { case (mat, completion) =>
          // every connection materializes a stream
          registry.connections.inc()
          registry.connected.inc()
          completion.onComplete(_ => registry.connected.dec())
          mat
        }
    }
  }
}
