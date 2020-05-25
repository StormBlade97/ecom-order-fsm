//package services
//import akka.actor.testkit.typed.scaladsl.ActorTestKit
//import akka.http.scaladsl.model.DateTime
//import model.{FulfillingOrder, InitialOrder, OrderStatus, PlanningOrder}
//import org.scalatest._
//import actors.OrderFSM
//import actors.OrderFSM.{Assign, CancelOrder, Complete, RequestState, RespondState}
//import shapeless.Generic
//
//class OrderFSMSpec
//  extends WordSpecLike
//    with BeforeAndAfterAll
//    with Matchers {
//  val testKit = ActorTestKit()
//  override def afterAll(): Unit = testKit.shutdownTestKit()
//  val initialOrder = InitialOrder(
//    id="order1",
//    status=OrderStatus.INITIAL,
//    createdAt=DateTime.now
//  )
//
//  "OrderFSM" should {
//    "while in state INITIAL" should {
//      "transition to PLANNING on ASSSIGN store" in {
//        val fsmActor = testKit.spawn(OrderFSM(initialOrder))
//        val probe = testKit.createTestProbe[OrderFSM.RespondState]("probe")
//        fsmActor ! Assign(to = "Store1")
//        fsmActor ! RequestState(replyTo = probe.ref)
//
//        val expectedOrder = Generic[PlanningOrder].from(
//          Generic[InitialOrder].to(initialOrder) :+ "Store1"
//        ).copy(status = OrderStatus.PLANNING)
//
//        probe.expectMessage(RespondState(expectedOrder))
//        testKit.stop(fsmActor)
//      }
//      "send output event to event collector" is pending
//      "not break upon receiving invalid input" in {
//        val fsmActor = testKit.spawn(OrderFSM(initialOrder));
//        fsmActor ! CancelOrder(DateTime.now, "Me", "For a test")
//        val probe = testKit.createTestProbe[RespondState]()
//        fsmActor ! RequestState(probe.ref)
//        probe.expectMessage(RespondState(initialOrder))
//      }
//    }
////    "while in state PLANNING" should {
////      val planningOrder = PlanningOrder("1", OrderStatus.PLANNING, DateTime.now, "sotre-1")
////      "prepares delivery labels" is pending
////      "transitions to FULFILLING on delivery docs READY" in {
////        val labelsUrl = s"ftp://labels/${initialOrder.id}"
////        val expectedOrder = Generic[FulfillingOrder].from(
////          Generic[PlanningOrder].to(planningOrder.copy(status = OrderStatus.FULFILLING)) :+ labelsUrl)
////        val fsmActor = testKit.spawn(OrderFSM(
////        planningOrder
////        ))
////        val probe = testKit.createTestProbe[OrderFSM.RespondState](name = "probe")
////        fsmActor ! OrderFSM.Ready(labelsUrl)
////        fsmActor ! RequestState(probe.ref)
////        probe.expectMessage(
////          RespondState(
////            expectedOrder
////          )
////        )
////      }
////      "send output event to event collector" is pending
////      "not break on invalid input" is pending
////    }
////    "while in state FULFILLING" should {
////      "transitions to COMPLETED on COMPLETE signal if allItemsPicked" in {
////        val fsmActor = testKit.spawn(OrderFSM(fulfillingOrder))
////        val probe = testKit.createTestProbe[OrderFSM.RespondState]("probe")
////        fsmActor ! OrderFSM.Complete(at = "now")
////        fsmActor ! RequestState(replyTo = probe.ref)
////
////        probe.expectMessage(RespondState(fulfillingOrder.copy(
////          status = OrderStatus.COMPLETED,
////          fulfilledAt = Some("now")
////        )))
////        testKit.stop(fsmActor)
////      }
////      "not transition to COMPLETED on COMPLETE signal if not allItemsPicked" is pending
////      "transition to CANCELLED on CANCEL_ORDER signal if allItemsCancelled" in {
////        val fsmActor = testKit.spawn(OrderFSM(fulfillingOrder))
////        val probe = testKit.createTestProbe[OrderFSM.RespondState]()
////        fsmActor ! CancelOrder("Now", "Me", "Testing")
////        fsmActor ! RequestState(probe.ref)
////
////        probe.expectMessage(RespondState(fulfillingOrder.copy(
////          status = OrderStatus.CANCELLED,
////          cancellationReason = Some("Testing"),
////          fulfilledAt = Some("Now")
////        )))
////      }
////      "not break on invalid input" is pending
////    }
////    "while in state COMPLETED" should {
////      "not break on any input" is pending
////    }
////    "while in state CANCELLED" should {
////      "not break on any input" is pending
////    }
//  }
//}