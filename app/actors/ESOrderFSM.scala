package actors

import java.net.URL

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.persistence.typed.PersistenceId
import model.{CancelledOrder, CompletedOrder, FulfillingOrder, InitialOrder, InvalidOrder, Order}
import org.joda.time.DateTime
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import shapeless.Generic
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import services.CarrerIntegrator

import scala.concurrent.Future
import scala.util.{Failure, Success}

object ESOrderFSM {

  type Requester = ActorRef[OperationResult]

  sealed trait Command {
    val replyTo: ActorRef[OperationResult]
  }

  final case class AssignStore(replyTo: Requester, storeId: String) extends Command

  private final case class ReceiveDeliveryDocuments(replyTo: Requester, labelsUrl: URL)
    extends Command

  private final case class UnableToGetDeliveryDocuments(replyTo: Requester, reason: Throwable)
    extends Command

  final case class Complete(replyTo: Requester, at: DateTime, by: String) extends Command

  final case class CancelOrder(replyTo: Requester, at: DateTime, by: String, reason: String)
    extends Command

  final case class QueryState(replyTo: Requester) extends Command

  //  final case class PrintPickList(replyTo: ActorRef[OperationResult]) extends Command
  //  final case class PickLine(replyTo: ActorRef[OperationResult]) extends Command
  //  final case class CancelLine(replyTo: ActorRef[OperationResult]) extends Command
  //  final case class PrintDeliveryDoc(replyTo: ActorRef[OperationResult]) extends Command
  //  final case class ReturnLine(replyTo: ActorRef[OperationResult]) extends Command

  sealed trait OperationResult

  final case class Accepted(order: Order) extends OperationResult

  final case class Rejected(reason: String) extends OperationResult

  sealed trait Event

  final case class FulfillerAssigned(storeId: String) extends Event

  final case class OrderReadied(at: DateTime, labelsUrl: URL) extends Event

  final case class OrderInvalidated(at: DateTime, reason: String) extends Event

  final case class OrderCompleted(at: DateTime, by: String) extends Event

  final case class OrderCancelled(at: DateTime, by: String, reason: String) extends Event

  sealed trait State {
    val order: Order
  }

  final case class Initial(order: InitialOrder) extends State

  final case class Fulfilling(order: FulfillingOrder) extends State

  final case class Completed(order: CompletedOrder) extends State

  final case class Cancelled(order: CancelledOrder) extends State

  final case class Invalid(order: InvalidOrder) extends State


  def apply(order: InitialOrder, carrerIntegrator: CarrerIntegrator): Behavior[Command] = Behaviors.setup { ctx ⇒
    EventSourcedBehavior.withEnforcedReplies[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(order.id),
      emptyState = Initial(order),
      commandHandler = (state, cmd) =>
        state match {
          case Initial(InitialOrder(id, _, _)) ⇒
            ctx.log.info("Order is in state INITIAL")
            cmd match {
              case AssignStore(replyTo, storeId) => Effect.persist(FulfillerAssigned(storeId))
                                                          .thenRun((_: State) ⇒
                                                            ctx.pipeToSelf(carrerIntegrator.generateDeliveryLabel(id, storeId)) {
                                                              case Success(labelsUrl) ⇒ ReceiveDeliveryDocuments(ctx.system.ignoreRef, labelsUrl)
                                                              case Failure(error) ⇒ UnableToGetDeliveryDocuments(ctx.system.ignoreRef, error)
                                                            }
                                                          )
                                                          .thenReply(replyTo)(newState => Accepted(newState.order))
              case ReceiveDeliveryDocuments(_, labelsUrl) => Effect.persist(OrderReadied(DateTime.now, labelsUrl)).thenNoReply()
              case UnableToGetDeliveryDocuments(_, reason) => Effect.persist(OrderInvalidated(DateTime.now, reason.getMessage)).thenNoReply()
              case QueryState(replyTo) ⇒ Effect.reply(replyTo)(Accepted(state.order))
              case anyCommand => Effect.reply(anyCommand.replyTo) {
                Rejected(s"The command is not permitted while order is in state INITIAL")
              }
            }
          case Fulfilling(_) =>
            ctx.log.info("Order is in state FULFILLING")
            cmd match {
              case Complete(replyTo, at, by) => Effect.persist(OrderCompleted(at, by))
                                                      .thenReply(replyTo)(newState => Accepted(newState.order))
              case CancelOrder(replyTo, at, by, reason) => Effect.persist(OrderCancelled(at, by, reason))
                                                                 .thenReply(replyTo)(newState => Accepted(newState.order))
              case QueryState(replyTo) ⇒ Effect.reply(replyTo)(Accepted(state.order))
              case anyCommand => Effect.reply(anyCommand.replyTo) {
                Rejected(s"The command is not permitted while order is in state FULFILLING")
              }
            }
          case Completed(_) =>
            ctx.log.info("Order is in state COMPLETED")
            cmd match {
              case QueryState(replyTo) ⇒ Effect.reply(replyTo)(Accepted(state.order))
              case anyCommand => Effect.reply(anyCommand.replyTo) {
                Rejected(s"The command is not permitted while order is in state COMPLETED")
              }
            }
          case Cancelled(_) =>
            ctx.log.info("Order is in state CANCELLED")
            Effect.stop().thenNoReply()
          case Invalid(_) =>
            ctx.log.info("Order is in state INVALID")
            Effect.stop().thenNoReply()
        }
      ,
      eventHandler = (state, evt) => state match {
        case Initial(initialOrder) => evt match {
          case FulfillerAssigned(storeId) => Initial(initialOrder.copy(storeId = Some(storeId)))
          case OrderReadied(_, labelsUrl) =>
            Fulfilling(
              FulfillingOrder(
                initialOrder.id,
                initialOrder.createdAt,
                initialOrder.storeId.getOrElse("Unknown"),
                labelsUrl
              )
            )
          case OrderInvalidated(_, reason) ⇒ Invalid(
           InvalidOrder(
             initialOrder.id,
             initialOrder.createdAt,
             initialOrder.storeId.getOrElse("Undefined"),
             reason
           )
          )
          case _ => throw new IllegalStateException(s"unexpected event [$evt] in state [$state]")
        }
        case Fulfilling(fulfillingOrder) => evt match {
          case _: OrderCompleted => Completed(
            Generic[CompletedOrder].from(
              Generic[FulfillingOrder].to(fulfillingOrder) :+ DateTime.now
            )
          )
          case OrderCancelled(_, _, reason) => Cancelled(
            Generic[CancelledOrder].from(
              Generic[FulfillingOrder].to(fulfillingOrder) :+ DateTime.now :+ reason
            )
          )


          case _ => throw new IllegalStateException(s"unexpected event [$evt] in state [$state]"
          )
        }
        case otherStates ⇒ otherStates
      }
    )
  }
}

