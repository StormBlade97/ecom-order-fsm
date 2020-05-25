package jobs

import java.util.UUID

import actors.ESOrderFSM
import actors.ESOrderFSM.{Accepted, Fulfilling, Rejected}
import akka.actor.ActorSystem
import akka.actor.typed.{ActorRef, Scheduler}
import akka.actor.typed.scaladsl.AskPattern._
import akka.stream.scaladsl.{Sink, Source}
import javax.inject.{Inject, Singleton}
import model.{FulfillingOrder, InitialOrder, Order, OrderRepository}
import org.joda.time.DateTime
import services.ActorFacade

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

@Singleton
class Import @Inject()(
  implicit val actorFacade: ActorFacade,
  implicit val ec: ExecutionContext,
  implicit val scheduler: Scheduler,
  implicit val ac: ActorSystem,
  val orderDAO: OrderRepository
) {

  /* Imports order from an upstream system, as this application does not generate orders
     Retrieves orders from a source; then executes order  preparation tasks, i.e assign fulfiller, or arrange delivery materials
     In production system, we'll use akka stream to retrieve real-time update from a queue, such as SQS
     For this demo, we'll use a static list
   */
  implicit val timeout: akka.util.Timeout = 1.second
  val source = Source(1 to 5)
    .throttle(1, 1.seconds)
    .map { index ⇒
      val order = InitialOrder(id = UUID.randomUUID().toString, DateTime.now, None)
      println(s"Importing order ${order.id}")
      order
    }
    .mapAsync(1)(order ⇒
      actorFacade.getOrCreate(order.id).map(ref ⇒ (ref, order))
    )
    .mapAsync(1)(refAndOrder ⇒ {
      val (fsmRef, order) = refAndOrder
      fsmRef
        .ask((r: ESOrderFSM.Requester) ⇒ ESOrderFSM.Initialize(r, order))
        .flatMap {
          case Accepted(stateChangedOrder) ⇒ Future.successful(fsmRef → stateChangedOrder)
          case Rejected(reason) ⇒ {
            println(s"Failed to import order ${order.id} because: ${reason}")
            Future.failed(new Exception(reason))
          }
        }
    })
    // a simple mock of order logistic service, assigning order to a fulfiller
    .mapAsync(1)(result ⇒ {
      val (fsmRef, order) = result
      fsmRef
        .ask((r: ESOrderFSM.Requester) ⇒ ESOrderFSM.AssignStore(r, "store-1"))
        .flatMap {
          case Accepted(stateChangedOrder) ⇒ Future.successful(stateChangedOrder)
          case Rejected(reason) ⇒ {
            println(s"Order failed to prepare for fulfilment ${order.id} because: ${reason}")
            Future.successful(order)
          }
        }
    })
//    .mapAsync(1)(result ⇒ {
//      orderDAO.insert(result)
//    }
    .runWith(Sink.ignore)
}
