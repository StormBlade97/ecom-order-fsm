package services.OrderFSM.spec
import akka.actor.testkit
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import model.{Order, OrderStatus}
import org.scalatest.matchers.should.Matchers
import org.scalatest._

class AsyncTestingExampleSpec
  extends AsyncWordSpec
    with BeforeAndAfterAll
    with Matchers {
  val testKit = ActorTestKit()
  override def afterAll(): Unit = testKit.shutdownTestKit()
  val initialOrder = Order(
    id="order1",
    status=OrderStatus.INITIAL,
    storeId=None,
    fulfilledAt=None,
    labelsUrl=None,
    cancellationReason=None,
  )
}