import zio._
import zio.Console._
import zio.http._
import zio.http.netty.NettyConfig
import zio.http.netty.client.NettyClientDriver

object OriginalPerf extends ZIOAppDefault {
  def run = {
    ZIO.succeed(0)
    // val urlHttp = "http://google.com"
    // val urlHttps = "https://google.com"

    // def req(url: String) = for {
    //   resp <- Client.request(Request.get(url))
    //   _ <- resp.body.asString
    // } yield ()

    // for {
    //   oneHttp <- req(urlHttp).provide(Client.default).timed
    //   _ <- printLine(s"1 http = ${oneHttp._1.toMillis}ms / req")
    //   tenHttp <- req(urlHttp).repeatN(10).provide(Client.default).timed
    //   _ <- printLine(s"10 seq http = ${tenHttp._1.dividedBy(10).toMillis}ms / req")

    //   oneHttps <- req(urlHttps).provide(Client.default).timed
    //   _ <- printLine(s"1 https = ${oneHttps._1.toMillis}ms / req")
    //   tenHttps <- req(urlHttps).repeatN(10).provide(Client.default).timed
    //   _ <- printLine(s"10 seq https = ${tenHttps._1.dividedBy(10).toMillis}ms / req")

    //   clientLayer = (
    //     (
    //       DnsResolver.default ++
    //         (ZLayer.succeed(NettyConfig.default) >>> NettyClientDriver.live) ++
    //         ZLayer.succeed(Client.Config.default.withFixedConnectionPool(10))
    //     ) >>> Client.customized
    //   ).fresh

    //   onePool <- req(urlHttps).provide(clientLayer).timed
    //   _ <- printLine(s"1 https fixed pool = ${onePool._1.toMillis}ms / req")
    //   tenPool <- req(urlHttps).repeatN(10).provide(clientLayer).timed
    //   _ <- printLine(s"10 seq https fixed pool = ${tenPool._1.dividedBy(10).toMillis}ms / req")
    // } yield ()
  }
}
