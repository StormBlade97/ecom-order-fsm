package service
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import service.OrderFSM.{Assign, CancelOrder, Complete, InputEvent, PlanningFailed, Ready}
import model.{Order, OrderStatus}


object OrderFSM {
  sealed trait InputEvent
  final case class Assign(to: String) extends InputEvent
  final case class Ready(deliveryLabelsUrl: String) extends InputEvent
  final case class Complete(at: String) extends InputEvent
  final case class CancelOrder(at: String, by: String, cause: String) extends InputEvent
  final case class PlanningFailed(reason: Throwable) extends InputEvent
  final case class PrintPickList() extends InputEvent
  final case class CancelLine() extends InputEvent
  final case class PickLine() extends InputEvent
  final case class PrintDeliveryDoc() extends InputEvent

  def apply(order: Order): Behavior[OrderFSM.InputEvent] = Behaviors.setup {
    context =>
      order.status match {
        case OrderStatus.INITIAL => new OrderFSM(context).initial(order)
        case OrderStatus.PLANNING => new OrderFSM(context).planning(order)
        case OrderStatus.FULFILLING => new OrderFSM(context).fulfilling(order)
        case OrderStatus.COMPLETED => new OrderFSM(context).fulfilling(order)
        case OrderStatus.CANCELLED => new OrderFSM(context).fulfilling(order)
        case OrderStatus.INVALID => new OrderFSM(context).invalid(order)
      }
  }
}

class OrderFSM(context: ActorContext[InputEvent]) {
  def completeGuard(order: Order): Boolean = {
    true
  }

  def initial(order: Order): Behavior[InputEvent] = Behaviors.receiveMessagePartial {
    case Assign(to) => planning(order.copy(
      status = OrderStatus.PLANNING,
      storeId = Some(to)
    ))
  }

  def planning(order: Order): Behavior[InputEvent] = {
    context.log.info("Pre-processing order (aka generating delivery document)")
    Behaviors.receiveMessagePartial {
      case Ready(deliveryLabelsUrl: String) => fulfilling(order.copy(
        status = OrderStatus.FULFILLING,
        labelsUrl = Some(deliveryLabelsUrl)
      ))
      case PlanningFailed(reason) => invalid(order.copy(
        status = OrderStatus.INVALID,
        cancellationReason = Some(reason.getMessage)
      ))
    }
  }

  def fulfilling(order: Order): Behavior[InputEvent] = {
    context.log.info("Order is in fulfilling state")
    Behaviors.receiveMessagePartial {
      case Complete(at) if completeGuard(order) => completed(order.copy(
        fulfilledAt = Some(at),
        status = OrderStatus.COMPLETED
      ))
      case Complete(_) => Behaviors.same
      case CancelOrder(_, _, cause) => cancelled(order.copy(
        status = OrderStatus.CANCELLED,
        cancellationReason = Some(cause)
      ))
    }
  }

  def completed(order: Order): Behavior[InputEvent] = {
    context.log.info("Order is completed")
    Behaviors.unhandled
  }
  def cancelled(order: Order): Behavior[InputEvent] = {
    context.log.info("Order is cancelled")
    Behaviors.unhandled
  }
  def invalid(order: Order): Behavior[InputEvent] = {
    context.log.info("Order is invalid")
    Behaviors.unhandled
  }

}