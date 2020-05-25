package model

import java.net.URL

import model.OrderStatus.OrderStatus
import javax.inject.Singleton
import org.joda.time.DateTime
import play.api.libs.json._
import shapeless.Generic

import scala.concurrent.Future
import scala.util.Try

trait CommonJSONFormatterMixin {
  val pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
  implicit val dateFormat   = Format[DateTime](JodaReads.jodaDateReads(pattern), JodaWrites.jodaDateWrites(pattern))
  implicit val urlFormatter = Format[URL](
    Reads[URL] {
      case JsString(s) ⇒ Try[URL](new URL(s)).fold(
        (err: Throwable) ⇒ JsError(Seq(JsPath() → Seq(JsonValidationError(err.getMessage)))),
        (url: URL) ⇒ JsSuccess(url)
      )
    },
    Writes[URL](url ⇒ JsString(url.toString))
  )
}


case object OrderStatus extends Enumeration {
  type OrderStatus = Value
  val INITIAL,
  FULFILLING,
  COMPLETED,
  INVALID,
  CANCELLED = Value

  implicit val statusReads  = Reads.enumNameReads(OrderStatus)
  implicit val statusWrites = Writes.enumNameWrites
}

sealed trait Order {
  def id: String

  def status: OrderStatus
}

object Order {

  implicit object OrderWriter extends Writes[Order] {
    override def writes(order: Order): JsValue = order match {
      case i: InitialOrder ⇒ Json.toJson(i)(InitialOrder.f.writes)
      case f: FulfillingOrder ⇒ Json.toJson(f)(FulfillingOrder.f.writes)
      case c: CompletedOrder ⇒ Json.toJson(c)(CompletedOrder.f.writes)
      case ca: CancelledOrder ⇒ Json.toJson(ca)(CancelledOrder.f.writes)
      case inva: InvalidOrder ⇒ Json.toJson(inva)(InvalidOrder.f.writes)
    }
  }

  implicit val w: Writes[Order] = OrderWriter
  implicit val r: Reads[Order]  =
    JsPath.read[InitialOrder](InitialOrder.f).map(x ⇒ x: Order) orElse
      JsPath.read[FulfillingOrder](FulfillingOrder.f).map(x ⇒ x: Order) orElse
      JsPath.read[CompletedOrder](CompletedOrder.f).map(x ⇒ x: Order) orElse
      JsPath.read[CancelledOrder](CancelledOrder.f).map(x ⇒ x: Order) orElse
      JsPath.read[InvalidOrder](InvalidOrder.f).map(x ⇒ x: Order)
}


final case class InitialOrder private(status: OrderStatus, id: String, createdAt: DateTime, storeId: Option[String])
  extends Order

object InitialOrder extends CommonJSONFormatterMixin {
  def apply(
    id: String,
    createdAt: DateTime,
    storeId: Option[String]
  ): InitialOrder = new InitialOrder(OrderStatus.INITIAL, id, createdAt, storeId)

  implicit val f = Json.format[InitialOrder]
}

final case class FulfillingOrder(
  status: OrderStatus,
  id: String,
  createdAt: DateTime,
  storeId: String,
  labelsUrl: URL
) extends Order

object FulfillingOrder extends CommonJSONFormatterMixin {
  def apply(
    id: String,
    createdAt: DateTime,
    storeId: String,
    labelsUrl: URL
  ): FulfillingOrder = FulfillingOrder(OrderStatus.FULFILLING, id, createdAt, storeId, labelsUrl)


  implicit val f: Format[FulfillingOrder] = Json.format[FulfillingOrder]
}

final case class CompletedOrder private(
  status: OrderStatus = OrderStatus.COMPLETED,
  id: String,
  createdAt: DateTime,
  storeId: String,
  labelsUrl: URL,
  fulfilledAt: DateTime
) extends Order

object CompletedOrder extends CommonJSONFormatterMixin {
  def apply(
    id: String,
    createdAt: DateTime,
    storeId: String,
    labelsUrl: URL,
    fulfilledAt: DateTime
  ): CompletedOrder = new CompletedOrder(OrderStatus.COMPLETED, id, createdAt, storeId, labelsUrl, fulfilledAt)

  implicit val f = Json.format[CompletedOrder]
}

final case class CancelledOrder(
  status: OrderStatus,
  id: String,
  createdAt: DateTime,
  storeId: String,
  labelsUrl: URL,
  fulfilledAt: DateTime,
  cancellationReason: String,
) extends Order

object CancelledOrder extends CommonJSONFormatterMixin {
  def apply(
    id: String,
    createdAt: DateTime,
    storeId: String,
    labelsUrl: URL,
    fulfilledAt: DateTime,
    cancellationReason: String,
  ): CancelledOrder = CancelledOrder(OrderStatus.CANCELLED, id, createdAt, storeId, labelsUrl, fulfilledAt, cancellationReason)

  implicit val f = Json.format[CancelledOrder]
}

final case class InvalidOrder(
  status: OrderStatus,
  id: String,
  createdAt: DateTime,
  storeId: String,
  invalidReason: String,
) extends Order

object InvalidOrder extends CommonJSONFormatterMixin {
  def apply(
    id: String,
    createdAt: DateTime,
    storeId: String,
    invalidReason: String,
  ): InvalidOrder = InvalidOrder(OrderStatus.INVALID, id, createdAt, storeId, invalidReason)

  implicit val f = Json.format[InvalidOrder]
}

@Singleton
class OrderRepository() {
  private var orders: Map[String, Order] = Map.empty

  def getAll: Future[Set[Order]] = Future.successful(orders.values.toSet)

  def get(id: String): Future[Option[Order]] = {
    Future.successful(orders.get(id))
  }

  def get(statuses: OrderStatus*): Future[Set[Order]] = Future.successful(orders.values
                                                                                .toSet
                                                                                .filter(order => statuses.contains(order.status)))

  def upsert(order: Order): Future[Order] = Future.successful {
        orders = orders.updated(order.id, order)
        order
    }
}

