package controllers

import javax.inject.Inject
import model.{OrderRepository}
import actors.ESOrderFSM
import akka.actor.typed.scaladsl.AskPattern._
import exceptions.{ApplicationError, OperationNotPermitted}
import akka.actor.typed.Scheduler
import akka.util.Timeout
import org.joda.time.DateTime
import play.api.mvc.ControllerComponents
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext


class OrderController @Inject()(
  implicit val cc: ControllerComponents,
  implicit val ec: ExecutionContext,
  implicit val scheduler: Scheduler,
  val ordersDAO: OrderRepository,
  val actorFacade: services.ActorFacade
) extends AbstractController(cc) {

  private implicit val orderTransitionRequestRead: Reads[OrderStateTransitionRequest] =
    Json.reads[OrderStateTransitionRequest]

  private def validateJson[A: Reads] =
    parse.json.validate(jsVal ⇒ {
      println(jsVal)
      jsVal.validate[A].asEither.left.map(e => BadRequest(JsError.toJson(e)))
    }
    )

  case class OrderStateTransitionRequest(targetStatus: String, reason: Option[String])

  def getAll() = Action.async {
    ordersDAO.getAll.map { orders ⇒
      Ok(
        Json.toJson(orders.toList)
      )
    }
  }

  def getById(id: String) = Action.async {
    ordersDAO.get(id).map {
      case Some(result) ⇒
        Ok(Json.toJson(result))
      case None ⇒
        NotFound
    }
  }

  def transition(id: String) =
    Action.async(validateJson[OrderStateTransitionRequest]) { request ⇒
      implicit val timeout: Timeout = 3.seconds
      val status = request.body.targetStatus
      val reason = request.body.reason
      val user   = "system"
      actorFacade.getOrCreate("1")
      val opsResult = for {
        fsmRef ← actorFacade.getOrCreate(id)
        opsResult ← fsmRef.ask[ESOrderFSM.OperationResult](ref ⇒ status match {
          case "complete" ⇒ ESOrderFSM.Complete(ref, DateTime.now, user)
          case "cancel" ⇒ ESOrderFSM.CancelOrder(ref, DateTime.now, user, reason.getOrElse("Default test"))
          case _ ⇒ throw OperationNotPermitted("Operation not supported. Supported operations are 'complete' | 'cancel'")
          /* TODO: map to order line command */
        })
      } yield opsResult
      opsResult.map {
        case ESOrderFSM.Accepted(order) ⇒ Ok(Json.toJson(order))
        case ESOrderFSM.Rejected(reason) ⇒ throw OperationNotPermitted(reason)
      }.recover {
        case e: ApplicationError ⇒ BadRequest(
          Json.parse(
            s"""
               |{
               |   "error": "${e.message}"
               |}
               |""".stripMargin)
        )
      }
    }
}
