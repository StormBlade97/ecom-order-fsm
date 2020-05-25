package jobs

import actors.ESOrderFSM
import actors.ESOrderFSM._
import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.stream.OverflowStrategy
import akka.stream.scaladsl._
import javax.inject.{Inject, Singleton}
import model.OrderRepository

import scala.concurrent.Future
import scala.util.control.NonFatal

/* Events outputted by the order actors will be forwarded to this stream
* where centralised processing happens, such as writing to a read-heavy persistence storage,
* or sending off to other system, or push notifications. Etc
* */

@Singleton
class ExportStream @Inject()(implicit system: akka.actor.ActorSystem, ordersDAO: OrderRepository) {
  /* In this demo we use in-mem buffer and an actor as inlet.
  * For production, use persistent storage for event journal
  * An offset table can be used to keep track of the processed events
  * Multiple streams can be spinned up and run horizontally, this way. */
  implicit val ec = system.dispatcher
  val source = Source.actorRef[(ESOrderFSM.Event, ESOrderFSM.State)](
    // never complete the stream. It runs forever
    completionMatcher = PartialFunction.empty,
    // never fail the stream because of a message
    failureMatcher = PartialFunction.empty,
    bufferSize = 10000,
    overflowStrategy = OverflowStrategy.dropHead)

  val actorRef = source
      .map {evtAndState ⇒
        println(s"Export stream running ${evtAndState._1}")
        evtAndState
      }
    .filter(eventAndState ⇒ eventAndState._2 match {
      case Empty ⇒ false // Should not happen, but if it does, ignore the message
      case _ ⇒ true
    })
    .map {
      _._2
    }
    .map {
      // Empty case has been filtered
      case Initial(order) ⇒ order
      case Fulfilling(order) ⇒ order
      case Cancelled(order) ⇒ order
      case Completed(order) ⇒ order
      case Invalid(order) ⇒ order
    }
    .mapAsyncUnordered(8)(order ⇒ {
      // @TODO: Handle failure
      println(s"Updating read-side with ${order.status} order #${order.id}")
      ordersDAO.upsert(order).recover {
        case NonFatal(_) ⇒ Future.successful(())
      }
    })
    .to(Sink.ignore)
    .run()

  def getSource() = actorRefAdapter[(ESOrderFSM.Event, ESOrderFSM.State)](actorRef)
}
