package com.vtex.akkahttpseed.routes

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.Timeout
import com.vtex.akkahttpseed.actors.{QueueConnector, StockPriceConnector}
import com.vtex.akkahttpseed.models.DailyQuoteResult
import com.vtex.akkahttpseed.models.forms.GetQuoteModel
import com.vtex.akkahttpseed.models.marshallers.Implicits._
import com.vtex.akkahttpseed.models.response.QueueMessage

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
  * Created by felipe on 13/06/16.
  */
class QueueRoutes(
                   queueConnector: ActorRef,
                   stockPriceConnector: ActorRef)(implicit system: ActorSystem) {

  implicit val ec = system.dispatcher
  implicit val timeout = Timeout(10.seconds)
  implicit val mat = ActorMaterializer(ActorMaterializerSettings(system))

  def routes: Route = {
    path("writeToQueue") {
      post {
        entity(as[GetQuoteModel]) { formModel =>
          complete {
            sendMessage(formModel)
          }
        }
      }
    } ~
    path("readFromQueue" / IntNumber) { limit =>
      get {
        complete {
          receiveMessages(limit)
        }
      }
    } ~
    path("readFromQueue") {
      get {
        complete {
          receiveMessages(10)
        }
      }
    }
  }


  /**
    * This method sends a quote (stock price) from the company identified by $model.ticker,
    * at a random date.
    *
    * Note that this method uses `map` and `flatMap` to resolve futures, options, tries and other monads
    *
    * @param model
    * @return
    */
  private def sendMessage(model: GetQuoteModel): Future[HttpResponse] = {

    (stockPriceConnector ? StockPriceConnector.GetQuote(model.ticker, model.day, model.month, model.year))
      .mapTo[Try[Option[DailyQuoteResult]]]
      .flatMap {
        case Success(maybeResult) => {

          val ticker = model.ticker
          val date = s"${model.day}-${model.month}-${model.year}"
          maybeResult match {
            case Some(result) => {
              if (result.dataset.data.nonEmpty) {
                val (date, value) = result.dataset.data.head
                val queueMessage = s"value for $ticker on $date was USD $value"

                (queueConnector ? QueueConnector.SendMessage(queueMessage)).mapTo[Try[String]].flatMap {
                  case Success(messageId) => Marshal(QueueMessage(messageId, None)).to[HttpResponse]
                  case Failure(e) => Future(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(e.getMessage)))
                }
              } else {
                Future(HttpResponse(StatusCodes.NotFound, entity = HttpEntity(s"""Failed to find stock price for "$ticker" on $date""")))
              }
            }
            case None => Future(HttpResponse(StatusCodes.NotFound, entity = HttpEntity(s"""Failed to find stock price for "$ticker" on $date""")))
          }
        }
        case Failure(e) => Future(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(e.getMessage)))
      }
  }

  /**
    * This method returns up to `upTo` messages, that may be available for reading in the queue.
    *
    * @param upTo
    * @return
    */
  private def receiveMessages(upTo: Int) = {
    (queueConnector ? QueueConnector.ReceiveMessages(Some(upTo))).mapTo[Try[List[QueueMessage]]].flatMap {
      case Success(messages) => Marshal(messages).to[HttpResponse]
      case Failure(NonFatal(nf)) => Future(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(nf.getMessage)))
    }
  }

}

