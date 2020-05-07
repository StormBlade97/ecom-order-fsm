package services

import java.net.URL

import javax.inject.Inject
import model.InitialOrder

import scala.concurrent.{ExecutionContext, Future}

class CarrerIntegrator @Inject()(implicit val ec: ExecutionContext) {
  def generateDeliveryLabel(orderId: String, storeId: String): Future[URL] = Future {
    Thread.sleep(500);
    new URL(s"http://${storeId}/${orderId}");
  }
}
