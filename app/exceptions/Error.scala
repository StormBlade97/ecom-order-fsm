package exceptions

trait ApplicationError extends Throwable {
  val message: String
}
case class NoSuchOrder(message: String) extends ApplicationError
case class NoSuchActor(message: String) extends ApplicationError
case class ActorCreationFailed(message: String) extends ApplicationError
case class OperationNotPermitted(message: String) extends ApplicationError