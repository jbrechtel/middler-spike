package com.nevercertain.middler

import akka.actor._
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import java.net.InetSocketAddress
import java.io.FileWriter

object HttpProxyService {
  def apply(endpoint: InetSocketAddress): Props = {
    Props(new HttpProxyService(endpoint))
  }
}
class HttpProxyService(endpoint: InetSocketAddress) extends Actor with ActorLogging {

  import context.system
  IO(Tcp) ! Tcp.Bind(self, endpoint)

  override def receive: Receive = {
    case Tcp.Connected(_, _) =>
      log.info("Connected.")
      sender ! Tcp.Register(context.actorOf(ClientConnectionHandler(sender)))
  }
}

object ClientConnectionHandler {
  def apply(connection: ActorRef): Props = {
    Props(new ClientConnectionHandler(connection))
  }
}
class ClientConnectionHandler(connection: ActorRef) extends Actor with ActorLogging {

  context.watch(connection)

  override def receive: Receive = {
    case Tcp.Received(data) =>
      log.info("Got some datas from client")
      context.actorSelection("/user/log") ! ProxyRequest(data)
      val headers = parseHeaders(data)
      headers.get("Host") match {
        case Some(host) => context.actorOf(RemoteConnectionHandler(sender, data, host))
        case None       => log.info("bad request!")
      }
    case _: Tcp.ConnectionClosed =>
      log.info("connection closed.")
      context.stop(self)
    case Terminated(_) =>
      log.info("terminated.")
      context.stop(self)
  }

  private def parseHeaders(rawRequest: ByteString): Map[String, String] = {
    val str = rawRequest.utf8String.trim
    val headerLines = str.lines.drop(1)

    val headers = for(headerLine <- headerLines;
                      firstColon = headerLine.indexOf(":");
                      header     = headerLine.substring(0, firstColon);
                      value      = headerLine.substring(firstColon+1).trim)
                        yield (header, value)

    headers.toMap
  }
}

object RemoteConnectionHandler {
  def apply(client: ActorRef, data: ByteString, host: String): Props = {
    Props(new RemoteConnectionHandler(client, data, host))
  }
}

class RemoteConnectionHandler(client: ActorRef, data: ByteString, host: String) extends Actor with ActorLogging {
  import context.system

  val remoteEndpoint = new InetSocketAddress(host, 80)
  log.info("starting up remote conn handler")

  IO(Tcp) ! Tcp.Connect(remoteEndpoint)

  context.watch(client)

  override def receive: Receive = {
    case Tcp.Connected(_, _) =>
      log.info("connected to remote.")
      context.watch(sender)
      sender ! Tcp.Register(self)
      sender ! Tcp.Write(data)
    case Tcp.Received(data) =>
      log.info("got some datas from remote.")
      context.actorSelection("/user/log") ! ProxyResponse(data)
      client ! Tcp.Write(data)
    case _: Tcp.ConnectionClosed =>
      log.info("connection closed.")
      context.stop(self)
    case Terminated(_) =>
      log.info("terminated.")
      context.stop(self)
    case foo => log.info(s"got a $foo")
  }
}

object ProxyLogger {
  def apply(fw: FileWriter): Props = Props(new ProxyLogger(fw))
}

class ProxyLogger(fw: FileWriter) extends Actor with ActorLogging {
  override def receive: Receive = {
    case ProxyRequest(data)  => fw.write(data.utf8String.trim)
    case ProxyResponse(data) => fw.write(data.utf8String.trim)
  }
}

object FrontEndHTTP {
  def apply() = Props(new FrontEndHTTP)
}

class FrontEndHTTP extends Actor with ActorLogging {
  import spray.http._
  import spray.can.Http
  import spray.http.HttpHeaders._
  import spray.http.MediaTypes._

  lazy val content = {
    val stream = this.getClass.getClassLoader.getResourceAsStream("index.html")
    io.Source.fromInputStream(stream).mkString
  }

  def receive = {
    case Http.Connected(_, _) => sender ! Http.Register(self)
    case HttpRequest(HttpMethods.GET, Uri.Path("/"), _, _, _) => {
      val response = HttpResponse(entity = HttpEntity(ContentType(`text/html`), content))
      sender ! response
    }
  }
}

sealed abstract class ProxyMessage
case class ProxyRequest(data: ByteString) extends ProxyMessage
case class ProxyResponse(data: ByteString) extends ProxyMessage

object HttpProxyApp extends App {
  import spray.can.Http

  implicit val system = ActorSystem("http-proxy")
  val endpoint = new InetSocketAddress("localhost", 9080)
  system.actorOf(HttpProxyService(endpoint))

  val frontEnd = system.actorOf(FrontEndHTTP())
  IO(Http) ! Http.Bind(frontEnd, interface = "localhost", port = 9081)

  val writer = new FileWriter("proxy.log")
  val logger = system.actorOf(ProxyLogger(writer), "log")

  Console.readLine("Press enter to exit.")
  system.shutdown()
  writer.close()
}
