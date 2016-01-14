package io.buoyant.linkerd

import com.fasterxml.jackson.core.JsonParser
import com.twitter.finagle.{Filter, ServiceFactory, Stack}
import com.twitter.finagle.param.Label
import com.twitter.finagle.server.StackServer
import com.twitter.util.Time
import io.buoyant.router._
import java.net.InetSocketAddress

/**
 * Provides a protocol-agnostic interface for protocol-specific
 * configuration & initialization.  Adapts linkerd's [[Router]] with
 * `io.buoyant.router.Router` and [[Server]] to
 * `com.twitter.finagle.Server`.
 *
 * Furthermore a protocol may provide parsers for protocol-specific
 * configuration parameters.
 *
 * ProtocolInitializer modules may be loaded as a plugin at runtime by
 * [[ProtocolInitializers.load]].
 */
trait ProtocolInitializer {
  import ProtocolInitializer._

  /** The protocol name, as read from configuration. */
  def name: String

  /*
   * Router configuration & initialization
   */
  protected type RouterReq
  protected type RouterRsp

  /** The default protocol-specific router configuration */
  protected def defaultRouter: StackRouter[RouterReq, RouterRsp]

  /** Reads protocol-specific router params. */
  protected def routerParamsParser: Parsing.Params = Parsing.Params.Empty

  /**
   * Satisfies the protocol-agnostic linkerd Router interface by
   * wrapping the protocol-specific router stack.
   */
  private case class ProtocolRouter(
    router: StackRouter[RouterReq, RouterRsp],
    servers: Seq[Server] = Nil
  ) extends Router {
    def params = router.params
    def withParams(ps: Stack.Params): Router = copy(router = router.withParams(ps))

    def paramParser = Router.Params.parser.andThen(routerParamsParser)

    protected def withServers(ss: Seq[Server]): Router = copy(servers = ss)

    val protocol = ProtocolInitializer.this
    def initialize(): Router.Initialized = {
      if (servers.isEmpty) {
        val Label(name) = params[Label]
        throw new IllegalStateException(s"router '$name' has no servers")
      }
      val factory = router.factory()
      val adapted = adapter.andThen(factory)
      val servable = servers.map { server =>
        val stackServer = defaultServer.withParams(server.params)
        ServerInitializer(protocol, server.addr, stackServer, adapted)
      }
      InitializedRouter(protocol, params, factory, servable)
    }
  }

  def router: Router = ProtocolRouter(defaultRouter)
    .configured(Label(name))

  /*
   * Server initialization
   */
  protected type ServerReq
  protected type ServerRsp

  /** Adapts a server to a router */
  protected def adapter: Filter[ServerReq, ServerRsp, RouterReq, RouterRsp]

  /** The default protocol-specific server configuration */
  protected def defaultServer: StackServer[ServerReq, ServerRsp]

  /** Reads protocol-specific server params. */
  protected def serverParamsParser: Parsing.Params = Parsing.Params.Empty

  private case class ProtocolServer(
    params: Stack.Params
  ) extends Server {
    val protocol = ProtocolInitializer.this
    def withParams(ps: Stack.Params): Server = copy(params = ps)

    private[this] def parser =
      Server.Params.parser.andThen(serverParamsParser)

    def configuredFrom(jp: JsonParser): Server =
      configured(parser.readObject(jp, params))
  }

  def server: Server = ProtocolServer(defaultServer.params)
    .configured(Label(name))
}

object ProtocolInitializer {

  /**
   * A [[ProtocolInitializer]] whose Server and Router have identical
   * request and response types.
   */
  trait Simple extends ProtocolInitializer {
    protected type Req
    protected type Rsp
    protected final type RouterReq = Req
    protected final type RouterRsp = Rsp
    protected final type ServerReq = Req
    protected final type ServerRsp = Rsp
    protected val adapter = Filter.identity[Req, Rsp]
  }

  /** Protocol-aware implementation of [[Router.Initialized]]. */
  private case class InitializedRouter[Req, Rsp](
    protocol: ProtocolInitializer,
    params: Stack.Params,
    factory: ServiceFactory[Req, Rsp],
    servers: Seq[Server.Initializer]
  ) extends Router.Initialized {
    def name: String = params[Label].label
    def close(t: Time) = factory.close(t)
  }

  /** Protocol-aware implementation of [[Server.Initializer]]. */
  private case class ServerInitializer[Req, Rsp](
    protocol: ProtocolInitializer,
    addr: InetSocketAddress,
    server: StackServer[Req, Rsp],
    factory: ServiceFactory[Req, Rsp]
  ) extends Server.Initializer {
    def params = server.params
    def name: String = server.params[Label].label
    def ip = addr.getAddress
    def port = addr.getPort
    def serve() = server.serve(addr, factory)
  }
}