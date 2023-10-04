package example

import java.util.concurrent.TimeUnit
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

import zio.Console._
import zio._
import zio.http._
import zio.http.Client
import zio.http.netty.NettyConfig
import zio.http.netty.client.NettyClientDriver

import zio.profiling.sampling._

object Pool extends ZIOAppDefault {
  val test : ZIO[Client, Nothing, Any] = ZIO.succeed(())
  // var url = "https://google.com"
  var url = "https://1.1.1.1"

  val requestZIO : ZIO[Client, Throwable, Unit] = ZIO.scoped {
    for {
      resp <- Client.request(Request.get(url))
      _    <- resp.body.asString
    } yield ()
  }

  def clientLayer(clientConfig: ZClient.Config) = {
    val nettyConfig = NettyConfig.default.copy(
      shutdownQuietPeriodDuration = Duration.fromMillis(0),
      shutdownTimeoutDuration = Duration.fromMillis(0),
    )
    val driver = (ZLayer.succeed(nettyConfig).fresh >>> NettyClientDriver.live).fresh
    val dnsResolver = DnsResolver.default.fresh
    val config = ZLayer.succeed(clientConfig).fresh

    (config ++ driver ++ dnsResolver >>> Client.customized.fresh).fresh
  }

  val REPEAT = 0
  val POOL_SIZE = 100

  val programPool = requestZIO.provide(clientLayer(ZClient.Config.default.copy(connectionPool = zio.http.ConnectionPoolConfig.Fixed(POOL_SIZE)))).repeatN(REPEAT)
  val programNoPool = requestZIO.provide(clientLayer(ZClient.Config.default.disabledConnectionPool)).repeatN(REPEAT)

  val profilerPool = new SamplingProfiler()
  val profilerNoPool = new SamplingProfiler()

  def reqJava() : ZIO[Any, Throwable, Unit] = {
    def requestZIO(client: HttpClient) = for {
      request <- ZIO.succeed(HttpRequest.newBuilder.uri(URI.create(url)).GET.build)
      _       <- ZIO.attempt { client.send(request, HttpResponse.BodyHandlers.ofString) }
    } yield ()

    for {
      beginMs <- Clock.currentTime(TimeUnit.MILLISECONDS)
      javaClient = HttpClient.newHttpClient
      _ <- requestZIO(javaClient)
    } yield ()
  }

  def run = for {

    // _ <- reqJava
    // _ <- programNoPool

    // _ <- profilerNoPool
    // .profile(programNoPool)
    // .flatMap(_.stackCollapseToFile("profile-no-pool.folded"))

    _ <- profilerPool
    .profile(programPool)
    .flatMap(_.stackCollapseToFile("profile-pool.folded"))

  } yield ()

  /*

   KEY=no-pool
   flamegraph ./zio-http-example/profile-${KEY}.folded > /tmp/profile-${KEY}.svg && open /tmp/profile-${KEY}.svg

   KEY=pool
   flamegraph ./zio-http-example/profile-${KEY}.folded > /tmp/profile-${KEY}.svg && open /tmp/profile-${KEY}.svg

   */
}
