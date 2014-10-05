package net.liftmodules.ng

import Angular.NgModel
import js.JsonDeltaFuncs._
import net.liftweb._
import json.{JsonParser, DefaultFormats, parse}
import json.Serialization._
import json.JsonAST._
import common._
import http.SHtml._
import http.js._
import http.S
import JE._
import JsCmds._
import scala.xml.NodeSeq

/**
 * Simple binding actor for creating a binding actor in one line
 *
 * @param bindTo The client `\$scope` element to bind to
 * @param initialValue Initial value on session initialization
 * @param onClientUpdate Callback to execute on each update from the client
 * @param clientSendDelay Milliseconds for the client to delay sending updates, allowing them to batch into one request. Defaults to 1 second (1000)
 * @tparam M The type of the model to be used in this actor
 */
abstract class SimpleNgModelBinder[M <: NgModel : Manifest]
  (val bindTo:String, val initialValue:M, override val onClientUpdate:M=>M = { m:M => m }, override val clientSendDelay:Int = 1000)
  extends NgModelBinder[M]{
  direction:BindDirection =>
}

sealed trait BindDirection {
  def toClient = false
  def toServer = false
}
trait BindingToClient extends BindDirection {
  override val toClient = true
}
trait BindingToServer extends BindDirection {
  override val toServer = true
}

sealed trait BindingScope {
  def sessionScope = false
}
trait SessionScope extends BindingScope {
  override def sessionScope = true
}

/**
  * CometActor which implements binding to a model in the target \$scope.
  * While a trait would be preferable, we need the type constraint in order
  * for lift-json to deserialize messages from the client.
  * @tparam M The type of the model to be used in this actor
  */
abstract class NgModelBinder[M <: NgModel : Manifest] extends AngularActor with BindingScope {
  self:BindDirection  =>
  import Angular._

  /** The client `\$scope` element to bind to */
  def bindTo: String

  /** Initial value on session initialization */
  def initialValue: M

  /** Milliseconds for the client to delay sending updates, allowing them to batch into one request */
  def clientSendDelay: Int = 1000

  /** Callback to execute on each update from the client */
  def onClientUpdate: M => M = {m:M => m}

  // This must be lazy so that it is invoked only after name is set.
  private lazy val guts =
    if(toServer && toClient && sessionScope)
      if(name.isDefined) new TwoWaySessionNamed else new TwoWaySessionUnnamed
    else if(toServer && toClient && !sessionScope)
      new TwoWayRequestScoped
    else if(toClient)
      new ToClientGuts
    else
      new ToServerGuts

  override def fixedRender = nodesToRender ++ guts.render

  override def lowPriority = guts.receive

  /** Abstracting the guts of our actor. */
  private[ng] trait BindingGuts {
    def receive: PartialFunction[Any, Unit]

    def render: NodeSeq

  }

  /** Called after an update from Client.  Input is the client ID where the update originated from */
  private type AfterUpdateFn = Box[String] => Unit
  /** Called to send a JsCmd to the client */
  private type SendCmdFn = JsCmd => Unit
  /** Called to handle JSON from the client */
  private type JsonHandlerFn = String => Unit

  private class ToServerGuts extends BindingGuts with ScalaUtils {
    override def render = Script(buildCmd(root = false,
      renderCurrentState &
      renderThrottleCount &
      watch(timeThrottledCall(sendToServer(handleJson)))
      // TODO: Figure out how to ignore initial $watch
    ))

    private def handleJson:JsonHandlerFn = { json =>
      fromClient(json, Empty, afterUpdate)
    }

    override def receive = empty_pf
    
    private def afterUpdate:AfterUpdateFn = id => {}
  }

  private class ToClientGuts extends BindingGuts {
    override def render = Script(buildCmd(root = false, renderCurrentState))

    override def receive = receiveFromServer(sendDiff) orElse receiveToClient

    private def sendDiff:SendCmdFn = cmd => self ! ToClient(cmd)
  }

  private class TwoWayRequestScoped extends BindingGuts {
    override def render = Script(buildCmd(root = false,
      renderCurrentState &
      renderThrottleCount &
      SetExp(JsVar("s()." + lastServerVal + bindTo), JsVar("s()." + bindTo)) & // This prevents us from sending a server-sent value back to the server when doing 2-way binding
      watch(ifNotServerEcho(timeThrottledCall(sendToServer(handleJson))))
    ))

    private def handleJson:JsonHandlerFn = { json =>
      fromClient(json, Empty, afterUpdate)
    }

    private def afterUpdate:AfterUpdateFn = id => {}

    override def receive = receiveFromServer(sendDiff) orElse receiveToClient

    private def sendDiff:SendCmdFn = cmd => self ! ToClient(cmd)
  }

  /** Guts for the unnamed binding actor which exits per session and allows the models to be bound together */
  private class TwoWaySessionUnnamed extends BindingGuts {

    override def render = Script(buildCmd(root = false,
      renderCurrentState &
      renderThrottleCount &
      SetExp(JsVar("s()." + lastServerVal + bindTo), JsVar("s()." + bindTo)) // This prevents us from sending a server-sent value back to the server when doing 2-way binding
    ))

    override def receive = receiveFromServer(sendDiff(Empty)) orElse receiveFromClient(afterUpdate)

    private def sendDiff(exclude:Box[String]):SendCmdFn = { cmd =>
      for {
        t <- theType
        session <- S.session
        comet <- session.findComet(t)
        if comet.name.isDefined  // Never send to unnamed comet. It doesn't handle these messages.
        if comet.name != exclude // Send to all clients but the originating client (if not Empty)
      } { comet ! ToClient(cmd) }

      // If we don't poke, then next time we are rendered, it won't contain the latest state
      poke()
    }

    private def afterUpdate(exclude:Box[String]): Unit = {
      val cmd = buildDiff(stateJson)
      sendDiff(exclude)(cmd)
    }
  }

  /** Guts for the named binding actor which exists per page and facilitates models to a given rendering of the page */
  private class TwoWaySessionNamed extends BindingGuts {
    override def render = Script(buildCmd(root = false, watch(ifNotServerEcho(timeThrottledCall(sendToServer(sendToSession))))))

    override def receive = receiveToClient

    private def sendToSession:JsonHandlerFn = json => for {
      session <- S.session
      cometType <- theType
      comet <- session.findComet(cometType, Empty)
      clientId <- name
    } { comet ! FromClient(json, Full(clientId)) }
  }
  
  private val lastServerVal = "net_liftmodules_ng_last_val_"
  private val queueCount = "net_liftmodules_ng_queue_count_"

  private var stateModel: M = initialValue
  private var stateJson: JValue = toJValue(stateModel)

  private def receiveFromClient(afterUpdate: AfterUpdateFn): PartialFunction[Any, Unit] = {
    case FromClient(json, id) => fromClient(json, id, afterUpdate)
  }

  private def receiveFromServer(sendFn: SendCmdFn): PartialFunction[Any, Unit] = {
    case m: M => fromServer(m, sendFn)
  }

  private def receiveToClient: PartialFunction[Any, Unit] = {
    case ToClient(cmd) => partialUpdate(buildCmd(root = false, cmd))
  }

  private def fromServer(m: M, sendFn: SendCmdFn) = {
    val mJs = toJValue(m)
    val cmd = buildDiff(mJs)
    sendFn(cmd)
    stateJson = mJs
    stateModel = m
  }

  private def fromClient(json: String, clientId:Box[String], afterUpdate: AfterUpdateFn) = {
    //    implicit val formats = DefaultFormats
    //    implicit val mf = manifest[String]
    import js.ToWithExtractMerged
    val parsed = JsonParser.parse(json)
    val jUpdate = parsed \\ "add"
    logger.debug("From Client: " + jUpdate)
    val updated = jUpdate.extractMerged(stateModel)
    logger.debug("From Client: " + updated)

    // TODO: Do something with the return value, or change it to return unit?
    onClientUpdate(updated)

    // TODO: When jUpdate becomes a diff, make sure we have the whole thing
    stateModel = updated
    stateJson = toJValue(updated)

    afterUpdate(clientId)
  }


  private def toJValue(m: M): JValue = {
    implicit val formats = DefaultFormats
    m match {
      case m: NgModel if m != null => parse(write(m))
      case e => JNull
    }
  }

  private def buildDiff(mJs: JValue) = {
    val diff = stateJson dfn mJs

    diff(JsVar("s()." + bindTo)) & // Send the diff
      SetExp(JsVar("s()." + lastServerVal + bindTo), JsVar("s()." + bindTo)) // And remember what we sent so we can ignore it later
  }


  private def renderCurrentState = SetExp(JsVar("s()." + bindTo), stateJson) // Send the current state with the page
  private val renderThrottleCount = SetExp(JsVar("s()." + queueCount + bindTo), JInt(0)) // Set the last server val to avoid echoing it back

  private def watch(f:JsCmd):JsCmd = Call("s().$watch", JString(bindTo), AnonFunc("n,o", f), JsTrue) // True => Deep comparison

  private def ifNotServerEcho(f:JsCmd):JsCmd =
  // If the new value, n, is not equal to the last server val, send it.
    JsIf(JsNotEq(JsVar("n"), JsRaw("s()." + lastServerVal + bindTo)),
      f,
      // else remove our last saved value so we can forget about it
      SetExp(JsVar("s()." + lastServerVal + bindTo), JsNull)
    )

  private def timeThrottledCall(f:JsCmd):JsCmd =
    JsCrVar("c", JsVar("s()." + queueCount + bindTo + "++")) &
      Call("setTimeout", AnonFunc(
        JsIf(JsEq(JsVar("c+1"), JsVar("s()." + queueCount + bindTo)), f)
      ), JInt(clientSendDelay))

  private def sendToServer(handler: JsonHandlerFn):JsCmd = JsCrVar("u", Call("JSON.stringify", JsVar("{add:n}"))) &
    ajaxCall(JsVar("u"), jsonStr => {
      logger.debug("Received string: "+jsonStr)
      handler(jsonStr)
      Noop
    })
}

private[ng] case class FromClient(json: String, clientId: Box[String])
private[ng] case class ToClient(cmd: JsCmd)

