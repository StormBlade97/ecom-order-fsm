package model

import java.util.UUID
import model.OrderStatus.OrderStatus

case object OrderStatus extends Enumeration {
type OrderStatus = Value
  val INITIAL,
      PLANNING,
      FULFILLING,
      COMPLETED,
      INVALID,
      CANCELLED = Value
}

case class Order(
                id: String,
                status: OrderStatus,
                storeId: Option[String],
                labelsUrl: Option[String],
                fulfilledAt: Option[String],
                cancellationReason: Option[String],
                )
