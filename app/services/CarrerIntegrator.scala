package services

import java.net.URL
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

class CarrerIntegrator @Inject()(implicit val ec: ExecutionContext) {
  def generateDeliveryLabel(orderId: String, storeId: String): Future[URL] = Future {
    Thread.sleep(500);
    if (Random.between(1, 100) < 30) throw new RuntimeException("Failed to generate delivery label")
    new URL(s"http://${storeId}/${orderId}");
  }
}
