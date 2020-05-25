package services

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.AskPattern._
import actors.ESOrderFSM
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout
import exceptions.NoSuchOrder
import javax.inject.{Inject, Singleton}
import jobs.ExportStream
import model.{InitialOrder, OrderRepository}
import org.joda.time.DateTime

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.Try

/* Simple mock of a parent actor holding references to all running instances of
* the FSM actors in-memory. To scale, use akka-cluster as it provides specific
* cluster-bound Actors to handle creation, finding, and routing message to the right Order entity actor */
object ActorIntegration {
  type MaybeESOrderFSMActor = Either[Throwable, ActorRef[ESOrderFSM.Command]]
  sealed trait Command
  final case class Create(replyTo: ActorRef[MaybeESOrderFSMActor], id: String) extends Command

  def apply(carrerIntegrator: CarrerIntegrator, exportStream: ExportStream): Behavior[Command] = Behaviors.setup { context ⇒
    /* TODO: supervisory code in case FSM crashes */
    Behaviors.receiveMessage {
      case Create(replyTo, id) ⇒
        val name          = s"order-fsm-${id}"
        val maybeActorRef = Try(context.spawn(
          ESOrderFSM(id, carrerIntegrator, exportStream),
          name
        )).toEither
        replyTo ! maybeActorRef
        Behaviors.same
    }
  }
}

@Singleton
class ActorFacade @Inject()(implicit system: akka.actor.ActorSystem, carrerIntegrator: CarrerIntegrator, ordersDAO: OrderRepository, exportStream: ExportStream) {
  private          val typedSystem: ActorSystem[Nothing] = system.toTyped
  private          var actorsRef                         = Map[String, ActorRef[ESOrderFSM.Command]]()
  private          val integration                       = typedSystem.systemActorOf(ActorIntegration(carrerIntegrator, exportStream), "actor-integrator")
  private implicit val timeout    : Timeout              = 1.seconds
  private implicit val ec                                = typedSystem.executionContext
  private implicit val scheduler                         = typedSystem.scheduler

  def getOrCreate(id: String): Future[ActorRef[ESOrderFSM.Command]] = {
    actorsRef.get(id) match {
      case Some(ref) ⇒ Future.successful(ref)
      case None ⇒
        println(s"Found no actor for orderID ${id}. Attempting to create new")
        integration.ask[ActorIntegration.MaybeESOrderFSMActor](ref ⇒
          ActorIntegration.Create(ref, id))
                   .flatMap {
                     case Right(fsmRef) ⇒
                       actorsRef = actorsRef + (id → fsmRef)
                       Future.successful(fsmRef)
                     case Left(failure) ⇒ Future.failed(failure)
                   }
    }
  }
}
