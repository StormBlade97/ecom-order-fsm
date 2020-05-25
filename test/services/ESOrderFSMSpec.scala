package services

import java.net.URL
import java.util.UUID

import actors.ESOrderFSM
import actors.ESOrderFSM._
import akka.actor.testkit.typed.scaladsl._
import com.typesafe.config.ConfigFactory
import jobs.ExportStream
import model._
import org.joda.time.DateTime
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.mockito._
import org.scalatestplus.play.PlaySpec

import scala.concurrent.Future


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
  val mockExportStream = mock[ExportStream]
  override def afterAll(): Unit = testKit.shutdownTestKit()

  when(mockExportStream.getSource()).thenReturn(testKit.createTestProbe[(Event, State)].ref)
  def setup(order: Order, carrerIntegrator: CarrerIntegrator) = {
    val orderFSM = testKit.spawn(ESOrderFSM(order.id, mockCarrerIntegrator, mockExportStream))
    val probe    = testKit.createTestProbe[ESOrderFSM.OperationResult]
    orderFSM ! Initialize(testKit.system.ignoreRef, order)
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
    "when initiated" should {
      "transitions into corresponding state from order status" in {
        val fulfillingOrder   = FulfillingOrder(
          id = "fulfilling-order-2",
          createdAt = DateTime.now,
          storeId = "store-1",
          labelsUrl = new URL("http://example.com"),
        )
        val (orderFSM, probe) = setup(fulfillingOrder, mockCarrerIntegrator)
        val cancelTime        = DateTime.now
        orderFSM ! CancelOrder(probe.ref, cancelTime, "Me", "For test")
        probe.expectMessage(Accepted(CancelledOrder(
          fulfillingOrder.id,
          fulfillingOrder.createdAt,
          fulfillingOrder.storeId,
          fulfillingOrder.labelsUrl,
          cancelTime,
          "For test"
        )))
      }
    }
    "while in INITIAL state" should {
      "transitions into FULFILLING after assigned fulfiller and delivery docs ready" in {
        val (orderFSM, probe) = setup(initialOrder.copy(id = "order-2"), mockCarrerIntegrator)
        orderFSM ! AssignStore(replyTo = probe.ref, storeId = "store-1")
        probe.expectMessageType[Accepted]
        orderFSM ! QueryState(replyTo = probe.ref)

        probe.expectMessage(Accepted(
          FulfillingOrder("order-2", initialOrder.createdAt, "store-1", labelsUrl)
        ))
      }
      "transitions into INVALID after assigned fulfiller and delivery docs failed" in {
        when(mockCarrerIntegrator.generateDeliveryLabel("order-3", "store-1"))
          .thenReturn(Future.failed(new RuntimeException("An error")))

        val (orderFSM, probe) = setup(initialOrder.copy(id = "order-3"), mockCarrerIntegrator)
        orderFSM ! AssignStore(replyTo = probe.ref, storeId = "store-1")
        probe.expectMessageType[Accepted]
        orderFSM ! QueryState(replyTo = probe.ref)

        probe.expectMessage(Accepted(
          InvalidOrder("order-3", initialOrder.createdAt, "store-1", "An error")
        ))
      }
    }
    "while in FULFILLING state" should {
      val fulfillingOrder = FulfillingOrder(
        id = "fulfilling-order-1",
        createdAt = DateTime.now,
        storeId = "store-1",
        labelsUrl = new URL("http://example.com"),
      )
      "be able to complete" in {
        val (orderFSM, probe) = setup(fulfillingOrder, mockCarrerIntegrator)
        val completedAt       = DateTime.now
        orderFSM ! Complete(probe.ref, completedAt, "Me")

        probe.expectMessage(
          Accepted(
            CompletedOrder(
              fulfillingOrder.id,
              fulfillingOrder.createdAt,
              fulfillingOrder.storeId,
              fulfillingOrder.labelsUrl,
              completedAt,
            )
          )
        )
      }
      "be able to be cancelled" in {
        val order             = fulfillingOrder.copy(id = "fulfilling-order-3")
        val (orderFSM, probe) = setup(order, mockCarrerIntegrator)
        val cmd = CancelOrder(probe.ref, DateTime.now, "Me", "Something")
        orderFSM ! cmd
        probe.expectMessage(
          Accepted(
            CancelledOrder(
              order.id,
              order.createdAt,
              order.storeId,
              order.labelsUrl,
              cmd.at,
              cmd.reason
            )
          )
        )
      }
    }
    "upon reaching CANCELLED state" should {
      "allows query" is pending
      "reject all other command" is pending
    }
    "upon reaching INVALID state" should {
      "allows query" is pending
      "reject all other command" is pending
    }
    "while in COMPLETED state" should {
      "handles items returned" is pending
    }
    "keep its state" in {
      val initialOrder = InitialOrder("rehydrated-order-1", DateTime.now(), None);
      when(mockCarrerIntegrator.generateDeliveryLabel(initialOrder.id, "store-1")).thenReturn(Future.successful(labelsUrl))

      val (orderFSM, probe) = setup(initialOrder, mockCarrerIntegrator)

      orderFSM ! AssignStore(probe.ref, "store-1")
      probe.expectMessageType[Accepted]
      testKit.stop(orderFSM)

      val rehydratedFSM = testKit.spawn(ESOrderFSM(initialOrder.id, mockCarrerIntegrator, mockExportStream))
      rehydratedFSM ! QueryState(probe.ref)
      probe.expectMessage(
        Accepted(
          FulfillingOrder(
            initialOrder.id,
            initialOrder.createdAt,
            "store-1",
            labelsUrl
          )
        )
      )
    }
    "keep initiated data" in {
      val initTime          = DateTime.parse("2020-05-15T23:00:00")
      val initialOrder      = InitialOrder("rehydrated-order-3", initTime, None);
      val (orderFSM, probe) = setup(initialOrder, mockCarrerIntegrator)
      orderFSM ! QueryState(probe.ref)
      probe.expectMessageType[Accepted]
      testKit.stop(orderFSM)
      val rehydratedFSM = testKit.spawn(ESOrderFSM(initialOrder.id, mockCarrerIntegrator, mockExportStream))
      rehydratedFSM ! QueryState(probe.ref)
      probe.expectMessage(Accepted(initialOrder))
    }
  }
}
