package model

import play.api.libs.json._
import play.api.libs.functional.syntax._
case class Point(
  x: Int,
  y: Int,
  z: Int
)
object Point {
  implicit val f: Format[Point] = (
    (JsPath \ "x").format[Int] and
      (JsPath \ "y").format[Int] and
      (JsPath \ "y").format[Int]
  )(Point.apply, unlift(Point.unapply))

}

object P extends App {
  val point1 = Point(1,2,3)
  val jsoned = Json.toJson(point1)
  print(jsoned)
}