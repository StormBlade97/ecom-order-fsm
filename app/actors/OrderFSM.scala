//package actors
//import akka.actor.typed.{ActorRef, Behavior}
//import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
//import akka.http.scaladsl.model.DateTime
//import actors.OrderFSM.{Assign, CancelOrder, Complete, InputEvent, PlanningFailed, Ready, RequestState, RespondState}
//import model.{CancelledOrder, CompletedOrder, FulfillingOrder, InitialOrder, InvalidOrder, Order, OrderStatus, PlanningOrder}
//import shapeless._
//
//object OrderFSM {
//  sealed trait InputEvent
//  final case class RequestState(replyTo: ActorRef[RespondState]) extends InputEvent
//  final case class Assign(to: String) extends InputEvent
//  final case class Ready(deliveryLabelsUrl: String) extends InputEvent
//  final case class Complete(at: DateTime) extends InputEvent
//  final case class CancelOrder(at: DateTime, by: String, cause: String) extends InputEvent
//  final case class PlanningFailed(reason: Throwable) extends InputEvent
//  final case class PrintPickList() extends InputEvent
//  final case class CancelLine() extends InputEvent
//  final case class PickLine() extends InputEvent
//  final case class PrintDeliveryDoc() extends InputEvent
//
//  sealed trait OutputEvent
//  final case class RespondState(order: Order)
//
//  def apply(order: Order): Behavior[OrderFSM.InputEvent] = Behaviors.setup {
//    context =>
//      order match {
//        case order: InitialOrder => new OrderFSM(context).initial(order)
//        case order: PlanningOrder => new OrderFSM(context).planning(order)
//        case order: FulfillingOrder => new OrderFSM(context).fulfilling(order)
//        case order: CompletedOrder => new OrderFSM(context).completed(order)
//        case order: CancelledOrder => new OrderFSM(context).cancelled(order)
//        case order: InvalidOrder => new OrderFSM(context).invalid(order)
//      }
//  }
//}
//
//class OrderFSM(context: ActorContext[InputEvent]) {
//  def completeGuard(order: Order): Boolean = {
//    true
//  }
//
//  def initial(order: InitialOrder): Behavior[InputEvent] = Behaviors.receiveMessagePartial {
//    case RequestState(replyTo) =>
//      replyTo ! RespondState(order)
//      Behaviors.same
//    case Assign(to) => planning(
//      Generic[PlanningOrder].from(Generic[InitialOrder].to(order.copy(status = OrderStatus.PLANNING)) :+ to)
//    )
//  }
//
//  def planning(order: PlanningOrder): Behavior[InputEvent] = {
//    context.log.info("Pre-processing order (aka generating delivery document)")
//    Behaviors.receiveMessagePartial {
//      case RequestState(replyTo) =>
//        replyTo ! RespondState(order)
//        Behaviors.same
//      case Ready(deliveryLabelsUrl: String) => fulfilling(
//        Generic[FulfillingOrder].from(
//           Generic[PlanningOrder].to(order.copy(status = OrderStatus.FULFILLING)) :+ deliveryLabelsUrl
//        )
//      )
//      case PlanningFailed(reason) => invalid(
//        Generic[InvalidOrder].from( Generic[PlanningOrder].to(order):+ reason.getMessage).copy(status = OrderStatus.INVALID)
//      )
//    }
//  }
//
//  def fulfilling(order: FulfillingOrder): Behavior[InputEvent] = {
//    Behaviors.receiveMessagePartial {
//      case RequestState(replyTo) =>
//        replyTo ! RespondState(order)
//        Behaviors.same
//      case Complete(at) =>
//        completed(
//          Generic[CompletedOrder].from(
//            Generic[FulfillingOrder].to(order) :+ at
//          ).copy(status = OrderStatus.COMPLETED)
//        )
//      case CancelOrder(at, _, cause) => cancelled(
//        Generic[CancelledOrder].from(
//          Generic[FulfillingOrder].to(order) :+ at :+ cause
//        )
//      )
//      case _ => Behaviors.same
//    }
//  }
//
//
//  def completed(order: CompletedOrder): Behavior[InputEvent] = {
//    println("Order is completed")
//    Behaviors.receiveMessage {
//      case RequestState(replyTo) =>
//        replyTo ! RespondState(order)
//        Behaviors.same
//      case _ => Behaviors.same
//    }
//  }
//  def cancelled(order: CancelledOrder): Behavior[InputEvent] = {
//    context.log.info("Order is cancelled")
//    Behaviors.receiveMessage {
//      case RequestState(replyTo) =>
//        replyTo ! RespondState(order)
//        Behaviors.same
//      case _ => Behaviors.same
//    }
//  }
//  def invalid(order: InvalidOrder): Behavior[InputEvent] = {
//    context.log.info("Order is invalid")
//    Behaviors.unhandled
//  }
//
//}