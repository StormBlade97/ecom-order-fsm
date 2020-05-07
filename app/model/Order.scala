package model

import java.net.URL

import model.OrderStatus.OrderStatus
import javax.inject.Singleton
import org.joda.time.DateTime

case object OrderStatus extends Enumeration {
  type OrderStatus = Value
  val INITIAL,
  FULFILLING,
  COMPLETED,
  INVALID,
  CANCELLED = Value
}

trait Order {
  def id: String

  def status: OrderStatus
}


final case class InitialOrder(id: String, createdAt: DateTime, storeId: Option[String]) extends Order {
  val status: OrderStatus = OrderStatus.INITIAL
}

final case class FulfillingOrder(id: String, createdAt: DateTime, storeId: String, labelsUrl: URL) extends Order {
  val status: OrderStatus = OrderStatus.FULFILLING
}

final case class CompletedOrder(
  id: String,
  createdAt: DateTime,
  storeId: String,
  labelsUrl: URL,
  fulfilledAt: DateTime
) extends Order {
  val status: OrderStatus = OrderStatus.COMPLETED
}

final case class CancelledOrder(
  id: String,
  createdAt: DateTime,
  storeId: String,
  labelsUrl: URL,
  fulfilledAt: DateTime,
  cancellationReason: String,
) extends Order {
  val status: OrderStatus = OrderStatus.CANCELLED
}

final case class InvalidOrder(
  id: String,
  createdAt: DateTime,
  storeId: String,
  invalidReason: String,
) extends Order {
  val status: OrderStatus = OrderStatus.INVALID
}

@Singleton
class OrderRepository() {
  private val orders: Set[Order] = (1 to 100).map(_.toString).map {
    InitialOrder(_, DateTime.now, None)
  }.toSet

  def getAll: Set[Order] = orders

  def get(id: String): Option[Order] = orders.find(order => order.id == id)

  def get(statuses: OrderStatus*): Set[Order] = orders.filter(order => statuses.contains(order.status))
}
