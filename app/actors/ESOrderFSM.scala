package actors

import java.net.URL

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.persistence.typed.PersistenceId
import model.{CancelledOrder, CompletedOrder, FulfillingOrder, InitialOrder, InvalidOrder, Order}
import org.joda.time.DateTime
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.actor.typed.scaladsl.Behaviors
import jobs.ExportStream
import services.CarrerIntegrator

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object ESOrderFSM {

  type Requester = ActorRef[OperationResult]

  sealed trait Command {
    val replyTo: ActorRef[OperationResult]
  }

  final case class Initialize(replyTo: Requester, order: Order) extends Command

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

  final case class ReceivedOrder(order: Order) extends Event

  final case class FulfillerAssigned(storeId: String) extends Event

  final case class OrderReadied(at: DateTime, labelsUrl: URL) extends Event

  final case class OrderInvalidated(at: DateTime, reason: String) extends Event

  final case class OrderCompleted(at: DateTime, by: String) extends Event

  final case class OrderCancelled(at: DateTime, by: String, reason: String) extends Event

  sealed trait State

  // Represents the state where no order is bound to this FSM
  final case object Empty extends State

  final case class Initial(order: InitialOrder) extends State

  final case class Fulfilling(order: FulfillingOrder) extends State

  final case class Completed(order: CompletedOrder) extends State

  final case class Cancelled(order: CancelledOrder) extends State

  final case class Invalid(order: InvalidOrder) extends State

  def apply(orderId: String, carrerIntegrator: CarrerIntegrator, exportStream: ExportStream): Behavior[Command] = Behaviors.setup { ctx ⇒
    def persistThenExport(evt: Event) = Effect.persist(evt)
                                              .thenRun((newState: State) ⇒
                                                exportStream.getSource() ! (evt → newState)
                                              )

    EventSourcedBehavior.withEnforcedReplies[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(orderId),
      emptyState = Empty,
      commandHandler = (state, cmd) =>
        state match {
          case Empty ⇒
            ctx.log.info(s"Actor started for order ${orderId}")
            cmd match {
              case Initialize(replyTo, order) ⇒ persistThenExport(ReceivedOrder(order)).thenReply(replyTo) {
                case Initial(order) ⇒ Accepted(order)
                case Fulfilling(order) ⇒ Accepted(order)
                case Cancelled(order) ⇒ Accepted(order)
                case Completed(order) ⇒ Accepted(order)
                case Invalid(order) ⇒ Accepted(order)
              }
              case anyCommand ⇒ Effect.reply(anyCommand.replyTo)(Rejected("Operation not supported in state Empty"))
            }
          case Initial(initOrder@InitialOrder(_, id, _, _)) ⇒
            cmd match {
              case AssignStore(replyTo, storeId) => Effect.persist(FulfillerAssigned(storeId))
                                                          .thenRun((_: State) ⇒
                                                            ctx.pipeToSelf(carrerIntegrator.generateDeliveryLabel(id, storeId)) {
                                                              case Success(labelsUrl) ⇒ ReceiveDeliveryDocuments(replyTo, labelsUrl)
                                                              case Failure(error) ⇒ UnableToGetDeliveryDocuments(replyTo, error)
                                                            }
                                                          )
                                                          .thenReply(exportStream.getSource())((newState: State) ⇒
                                                            FulfillerAssigned(storeId) → newState
                                                          )
              case ReceiveDeliveryDocuments(replyTo, labelsUrl) => persistThenExport(OrderReadied(DateTime.now, labelsUrl))
                .thenReply(replyTo) {
                  case Fulfilling(newOrder) ⇒ Accepted(newOrder)
                }
              case UnableToGetDeliveryDocuments(replyTo, reason) => persistThenExport(OrderInvalidated(DateTime.now, reason.getMessage))
                .thenReply(replyTo) {
                  case Invalid(newOrder) ⇒
                    ctx.log.warn(s"Could not generate delivery doc for order ${newOrder.id}. Transition to Invalid")
                    Accepted(newOrder)
                }
              case QueryState(replyTo) ⇒ Effect.reply(replyTo)(Accepted(initOrder))
              case anyCommand => Effect.reply(anyCommand.replyTo) {
                Rejected(s"The command is not permitted while order is in state INITIAL")
              }
            }
          case Fulfilling(fulfillingOrder) =>
            cmd match {
              case Complete(replyTo, at, by) => persistThenExport(OrderCompleted(at, by))
                .thenReply(replyTo) {
                  case Completed(newOrder) ⇒ Accepted(newOrder)
                }
              case CancelOrder(replyTo, at, by, reason) => persistThenExport(OrderCancelled(at, by, reason))
                .thenReply(replyTo) {
                  case Cancelled(newOrder) ⇒ Accepted(newOrder)
                }
              case QueryState(replyTo) ⇒ Effect.reply(replyTo)(Accepted(fulfillingOrder))
              case anyCommand => Effect.reply(anyCommand.replyTo) {
                Rejected(s"The command is not permitted while order is in state FULFILLING")
              }
            }
          case Completed(completedOrder) =>
            cmd match {
              case QueryState(replyTo) ⇒ Effect.reply(replyTo)(Accepted(completedOrder))
              case anyCommand => Effect.reply(anyCommand.replyTo) {
                Rejected(s"The command is not permitted while order is in state COMPLETED")
              }
            }
          case Cancelled(cancelledOrder) =>
            cmd match {
              case QueryState(replyTo) ⇒ Effect.reply(replyTo)(Accepted(cancelledOrder))
              case anyCommand => Effect.reply(anyCommand.replyTo) {
                Rejected(s"The command is not permitted while order is in state CANCELLED")
              }
            }
          case Invalid(invalidOrder) =>
            cmd match {
              case QueryState(replyTo) ⇒ Effect.reply(replyTo)(Accepted(invalidOrder))
              case anyCommand => Effect.reply(anyCommand.replyTo) {
                Rejected(s"The command is not permitted while order is in state CANCELLED")
              }
            }
        }
      ,
      eventHandler = (state, evt) => state match {
        case Empty ⇒ evt match {
          case ReceivedOrder(order) ⇒ order match {
            case o: InitialOrder ⇒ Initial(order = o)
            case o: FulfillingOrder ⇒ ctx.log.info("Arrr"); Fulfilling(order = o)
            case o: CancelledOrder ⇒ ctx.log.info("Arrr12"); Cancelled(order = o)
            case o: InvalidOrder ⇒ Invalid(order = o)
          }
        }
        case Initial(initialOrder) => evt match {
          case FulfillerAssigned(storeId) =>
            ctx.log.info(s"Transiting into state Initial from Initial, due to event $evt")
            Initial(initialOrder.copy(storeId = Some(storeId)))
          case OrderReadied(_, labelsUrl) =>
            ctx.log.info(s"Transiting into state Fulfilling from Initial, due to event $evt")
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
          case OrderCompleted(at, _) => Completed(
            CompletedOrder(
              fulfillingOrder.id,
              fulfillingOrder.createdAt,
              fulfillingOrder.storeId,
              fulfillingOrder.labelsUrl,
              at
            )
          )
          case OrderCancelled(at, _, reason) => Cancelled(
            CancelledOrder(
              fulfillingOrder.id,
              fulfillingOrder.createdAt,
              fulfillingOrder.storeId,
              fulfillingOrder.labelsUrl,
              at,
              reason
            )
          )
          case _ => throw new IllegalStateException(s"unexpected event [$evt] in state [$state]"
          )
        }
        case otherStates ⇒ otherStates
      }
    ).onPersistFailure(
      SupervisorStrategy.restartWithBackoff(minBackoff = 10.seconds, maxBackoff = 60.seconds, randomFactor = 0.1))
  }
}

