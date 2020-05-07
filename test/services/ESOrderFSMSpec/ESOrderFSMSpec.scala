package services.ESOrderFSMSpec

import java.net.URL
import java.util.UUID

import actors.ESOrderFSM
import actors.ESOrderFSM.{Accepted, AssignStore, CancelOrder, Cancelled, OperationResult, QueryState, ReceiveDeliveryDocuments, OrderReadied, Rejected}
import org.scalatest.BeforeAndAfterAll
import akka.actor.testkit.typed.scaladsl._
import model.{CancelledOrder, FulfillingOrder, InitialOrder, InvalidOrder}
import org.joda.time.DateTime
import org.scalatestplus.mockito._
import org.scalatestplus.play.PlaySpec
import services.CarrerIntegrator

import scala.concurrent.Future
import org.mockito.Mockito._
import com.typesafe.config.ConfigFactory


class ESOrderFSMSpec
  extends PlaySpec with BeforeAndAfterAll with MockitoSugar {

  val config = ConfigFactory.parseString(
    s"""
      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
      akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      akka.persistence.snapshot-store.local.dir = "target/snapshot-${UUID.randomUUID().toString}"
  """).withFallback(ConfigFactory.load())
  ConfigFactory.load()

  val testKit              = ActorTestKit(customConfig = config)
  val mockCarrerIntegrator = mock[CarrerIntegrator]

  override def afterAll(): Unit = testKit.shutdownTestKit()

  def setup(initOrder: InitialOrder, carrerIntegrator: CarrerIntegrator) = {
    val orderFSM = testKit.spawn(ESOrderFSM(initOrder, mockCarrerIntegrator))
    val probe    = testKit.createTestProbe[ESOrderFSM.OperationResult]
    orderFSM → probe
  }

  "The order" should {
    val initialOrder = InitialOrder(
      id = "order-1",
      createdAt = DateTime.now(),
      storeId = None
    )

    val labelsUrl = new URL(s"http://labels")
    (1 to 2).foreach(num ⇒
      when(mockCarrerIntegrator.generateDeliveryLabel(s"order-$num", "store-1"))
        .thenReturn(Future.successful(labelsUrl))
    )

    "while in INITIAL state" should {
      "be assigned fulfiller" in {
        val (orderFSM, probe) = setup(initialOrder, mockCarrerIntegrator)
        orderFSM ! AssignStore(replyTo = probe.ref, storeId = "store-1")

        probe.expectMessage(ESOrderFSM.Accepted(
          initialOrder.copy(storeId = Some("store-1"))
        ))
      }
      "transitions into FULFILLING after delivery docs ready" in {
        val (orderFSM, probe) = setup(initialOrder.copy(id = "order-2"), mockCarrerIntegrator)
        orderFSM ! AssignStore(replyTo = probe.ref, storeId = "store-1")
        probe.expectMessageType[Accepted]
        orderFSM ! QueryState(replyTo = probe.ref)

        probe.expectMessage(Accepted(
          FulfillingOrder("order-2", initialOrder.createdAt, "store-1", labelsUrl)
        ))
      }
      "transitions into INVALID after delivery docs failed" in {
        when(mockCarrerIntegrator.generateDeliveryLabel("order-3", "store-1"))
          .thenReturn(Future.failed(new RuntimeException("An error")))

        val (orderFSM, probe) = setup(initialOrder.copy(id = "order-3"), mockCarrerIntegrator)
        orderFSM ! AssignStore(replyTo = probe.ref, storeId = "store-1")
        probe.expectMessageType[Accepted]
        orderFSM ! QueryState(replyTo = probe.ref)

        probe.expectMessage(Accepted(
          InvalidOrder("order-3", initialOrder.createdAt, "store-1", invalidReason = "An error")
        ))
      }
    }
    "while in FULFILLING state" should {
      "be able to complete" is pending
      "be able to be cancelled" is pending
    }
    "upon reaching CANCELLED state" should {
      "terminate" is pending
    }
    "upon reaching INVALID state" should {
      "terminate" is pending
    }
    "while in COMPLETED state" should {
      "handles items returned" is pending
    }
  }
}
