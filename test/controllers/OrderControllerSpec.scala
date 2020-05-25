package controllers

import java.net.URL

import actors.ESOrderFSM
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.scaladsl.Behaviors
import jobs.{ExportJobModule, ImportJobModule}
import model.{CancelledOrder, CompletedOrder, InitialOrder, Order, OrderRepository, OrderStatus}
import org.joda.time.DateTime
import org.scalatestplus.mockito._
import org.mockito.Mockito.when
import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import play.api.test.Helpers._
import play.api.test._
import org.joda.time.DateTimeUtils
import play.api.http.HeaderNames
import play.api.inject.guice.GuiceApplicationBuilder
import services.ActorFacade
import play.api.inject.bind

import scala.concurrent.{ExecutionContext, Future}

class OrderControllerSpec extends PlaySpec with GuiceOneAppPerTest with Injecting with MockitoSugar {
  val ordersDAO  : OrderRepository = mock[model.OrderRepository]
  val actorFacade: ActorFacade     = mock[services.ActorFacade]

  override def fakeApplication() = new GuiceApplicationBuilder()
    .disable[ImportJobModule]
    .disable[ExportJobModule]
    .overrides(bind[OrderRepository].toInstance(ordersDAO))
    .overrides(bind[ActorFacade].toInstance(actorFacade))
    .build()

  val testKit = ActorTestKit()
  implicit val cc              : ControllerComponents = stubControllerComponents()
  implicit val system                                 = testKit.system
  implicit val scheduler                              = system.scheduler
  implicit val executionContext: ExecutionContext     = system.executionContext

  DateTimeUtils.setCurrentMillisFixed(10L)

  val mockOrders: Set[Order] = (1 to 5).map(_.toString).map {
    InitialOrder(status = OrderStatus.INITIAL, _, DateTime.now, None)
  }.toSet

  def setupOrderFsm(order: Order, expectedTransformedOrder: Order) = {
    when(actorFacade.getOrCreate("1")).thenReturn(Future.successful {
      testKit.spawn(
        Behaviors.receiveMessage[ESOrderFSM.Command] {
          case anyCommand ⇒ anyCommand.replyTo ! ESOrderFSM.Accepted(expectedTransformedOrder)
            Behaviors.same
        })
    })
  }

  "OrderController GET" should {
    when(ordersDAO.getAll).thenReturn(Future.successful(mockOrders))

    "get all orders as json" in {
      val request = FakeRequest(GET, "/orders")
      val result  = route(app, request).get

      status(result) mustBe OK
      contentType(result) mustBe Some("application/json")
      contentAsJson(result) mustEqual Json.toJson(mockOrders)
    }

    "get a single order as json" should {
      val expectedOrder = mockOrders.find(o ⇒ o.id == "1").get match {
        case a: InitialOrder ⇒ a
      }
      "return order if found" in {
        when(ordersDAO.get("1")).thenReturn(Future.successful(Some(expectedOrder)))
        val request = FakeRequest(GET, "/orders/1")
        val result  = route(app, request).get
        status(result) mustBe OK
        contentType(result) mustBe Some("application/json")
        contentAsJson(result) mustEqual Json.toJson(expectedOrder)
      }
      "handle not found" in {
        when(ordersDAO.get("1342")).thenReturn(Future.successful(None))

        val request = FakeRequest(GET, "/orders/1342")
        val result  = route(app, request).get

        status(result) mustBe NOT_FOUND

      }
    }
    "get a single order history as json" is pending
  }


  "OrderController transition PATCH" should {
    val expectedOrder = mockOrders.find(o ⇒ o.id == "1").get match {
      case a: InitialOrder ⇒ a
    }
    when(ordersDAO.get("1")).thenReturn(Future.successful(Some(expectedOrder)))
    "mark as fulfilled" in {
      val request               = FakeRequest(PATCH, "/orders/1/transition").withBody(Json.obj("targetStatus" → "complete"))
                                                                            .withHeaders(HeaderNames.CONTENT_TYPE -> "application/json");
      val expectedCompleteOrder = CompletedOrder(
        expectedOrder.id,
        expectedOrder.createdAt,
        storeId = "store-1",
        labelsUrl = new URL("http://labels"),
        fulfilledAt = DateTime.now
      )
      setupOrderFsm(expectedOrder, expectedCompleteOrder)
      val result = route(app, request).get
      status(result) mustBe OK
      contentType(result) mustBe Some("application/json")
      contentAsJson(result) mustEqual Json.toJson(expectedCompleteOrder)
    }

    "mark as cancelled" in {
      val fulfillingOrder = expectedOrder.copy(id="2")
      val expectedCancelledOrder = CancelledOrder(
        id = fulfillingOrder.id,
        createdAt = fulfillingOrder.createdAt,
        storeId = fulfillingOrder.storeId.getOrElse(""),
        labelsUrl = new URL("http://labels.com"),
        fulfilledAt = DateTime.now,
        cancellationReason = "For test"
      )
      setupOrderFsm(fulfillingOrder, expectedCancelledOrder )
      val request                = FakeRequest(PATCH, "/orders/1/transition").withBody(Json.obj("targetStatus" → "cancel"))
                                                                             .withHeaders(HeaderNames.CONTENT_TYPE -> "application/json");
      val result                 = route(app, request).get
      status(result) mustBe OK
      contentType(result) mustBe Some("application/json")
      contentAsJson(result) mustEqual Json.toJson(expectedCancelledOrder)
    }
  }
}
