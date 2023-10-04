package example

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.TimeUnit

import zio.Console._
import zio._

import zio.http._
import zio.http.netty.NettyConfig
import zio.http.netty.client.NettyClientDriver

// import zio.profiling.sampling._

object Perf extends ZIOAppDefault {
  val batchRepeatTimes = 10

  case class TestData(
    var sum: Long = 0,
    var times: Int = 0,
    var batchSum: Long = 0,
    var batchTimes: Int = 0) {

    def update(single: Long, batch: Long) {
      sum += single
      times += 1
      batchSum += batch
      batchTimes += batchRepeatTimes
    }

    def update(data: (Long, Long)) {
      update(data._1, data._2)
    }

    override def toString = {
      val singlePrint = if (times == 0) "N/A" else s"${sum / times} ms/req"
      val batchPrint = if (batchTimes == 0) "N/A" else s"${batchSum / batchTimes} ms/req"
      s"{single (${times} requests): ${singlePrint}}; batch (${batchTimes} requests): ${batchPrint}"
    }
  }

  var noPoolData = TestData()
  var javaData = TestData()
  var defaultData = TestData()

  val clientNoPoolLayer = {
    val config = ZLayer.succeed(Client.Config.default.disabledConnectionPool)
    val driver = (ZLayer.succeed(NettyConfig.defaultWithFastShutdown) >>> NettyClientDriver.live)
    val dnsResolver = DnsResolver.default
    (config ++ driver ++ dnsResolver >>> Client.customized)
  }

  val url = "https://google.com"  // poor google :)

  def reqZIO(clientLayer: ZLayer[Any, Throwable, Client]) : ZIO[Any, Throwable, (Long, Long)]  = {
    val requestZIO : ZIO[Client, Throwable, Unit] = ZIO.scoped {
      for {
        resp <- Client.request(Request.get(url))
        _    <- resp.body.asString
      } yield ()
    }

    for {
      beginMs <- Clock.currentTime(TimeUnit.MILLISECONDS)
      out <- clientLayer { for {
        // make single request and end clock
        _ <- requestZIO
        endMs <- Clock.currentTime(TimeUnit.MILLISECONDS)
        val single = endMs - beginMs

        // batch request
        batch <- requestZIO.repeatN(batchRepeatTimes).timed.map(_._1.toMillis)
      } yield (single, batch)
      }
    } yield out
  }

  def reqJava() : ZIO[Any, Throwable, (Long, Long)] = {
    def requestZIO(client: HttpClient) = for {
      request <- ZIO.succeed(HttpRequest.newBuilder.uri(URI.create(url)).GET.build)
      _       <- ZIO.attempt { client.send(request, HttpResponse.BodyHandlers.ofString) }
    } yield ()

    for {
      beginMs <- Clock.currentTime(TimeUnit.MILLISECONDS)
      val javaClient = HttpClient.newHttpClient
      _ <- requestZIO(javaClient)
      endMs <- Clock.currentTime(TimeUnit.MILLISECONDS)
      single <- ZIO.succeed(endMs - beginMs)

      batch <- requestZIO(javaClient).repeatN(batchRepeatTimes).timed.map(_._1.toMillis)
    } yield (single, batch)
  }

  var iteration = 0

  def run = ZIO.scoped {
    ZIO.iterate(0)(_ => true){iteration =>
      for {
        _ <- reqZIO(clientNoPoolLayer).map(noPoolData.update)
        _ <- reqJava.map(javaData.update)
        _ <- reqZIO(Client.default).map(defaultData.update)

        _ <- ZIO.when(iteration % 3 == 0){
          printLine(s"--- Iteration: ${iteration} ---") *>
          printLine(s"No connection pool: ${noPoolData}") *>
          printLine(s"Java HttpClient: ${javaData}") *>
          printLine(s"Default Client: ${defaultData}")
        }
      } yield (iteration+1)
    }
  }
}
