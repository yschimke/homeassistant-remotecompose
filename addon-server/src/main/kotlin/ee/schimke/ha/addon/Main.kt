package ee.schimke.ha.addon

import ee.schimke.ha.addon.auth.AddonAuth
import ee.schimke.ha.addon.auth.AddonAuthConfig
import ee.schimke.ha.addon.auth.CachingTokenValidator
import ee.schimke.ha.addon.auth.HaTokenValidator
import ee.schimke.ha.addon.auth.SUPERVISOR_INGRESS_ADDRESS
import ee.schimke.ha.addon.auth.SupervisorCoreUrl
import ee.schimke.ha.addon.bridge.HaSupervisorBridge
import ee.schimke.ha.addon.routes.dashboardRoutes
import ee.schimke.ha.addon.routes.healthRoutes
import ee.schimke.ha.addon.routes.streamRoute
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ee.schimke.ha.addon.Main")

fun main() {
  val cfg = AddonConfig.fromEnv()
  log.info("starting addon-server on :{}, ha={}", cfg.port, cfg.haBaseUrl)

  val bridge = HaSupervisorBridge(cfg.haBaseUrl, cfg.haAccessToken)
  bridge.start()

  val authHttp =
    HttpClient(ClientCIO) {
      install(HttpTimeout) {
        requestTimeoutMillis = 10_000
        connectTimeoutMillis = 5_000
      }
    }
  val authBaseUrl = cfg.authBaseUrl
  val coreUrl: suspend () -> String? =
    when {
      authBaseUrl != null -> ({ authBaseUrl })
      cfg.supervisorToken != null -> SupervisorCoreUrl(authHttp, cfg.supervisorToken)
      else -> ({ null })
    }
  val validator = CachingTokenValidator(HaTokenValidator(authHttp, coreUrl))
  val trustedProxies =
    if (cfg.supervisorToken != null) setOf(SUPERVISOR_INGRESS_ADDRESS) else emptySet()

  val server =
    embeddedServer(CIO, port = cfg.port, host = "0.0.0.0") {
      configure(bridge) {
        trustedProxyAddresses = trustedProxies
        tokenValidator = validator
      }
    }
  Runtime.getRuntime()
    .addShutdownHook(
      Thread {
        log.info("shutdown: stopping server + bridge")
        server.stop(1, 5, TimeUnit.SECONDS)
        kotlinx.coroutines.runBlocking { bridge.close() }
        authHttp.close()
      }
    )
  server.start(wait = true)
}

internal fun Application.configure(bridge: HaSupervisorBridge, auth: AddonAuthConfig.() -> Unit) {
  install(DefaultHeaders) { header("X-Powered-By", "ha-remotecompose") }
  install(CallLogging)
  install(AddonAuth, auth)
  install(WebSockets)
  install(ContentNegotiation) {
    json(
      Json {
        ignoreUnknownKeys = true
        isLenient = true
      }
    )
  }
  install(StatusPages) {
    exception<Throwable> { call, cause ->
      log.error("unhandled error", cause)
      call.respondText(
        text = """{"error":"${cause.message?.replace("\"", "\\\"")}"}""",
        contentType = io.ktor.http.ContentType.Application.Json,
        status = HttpStatusCode.InternalServerError,
      )
    }
  }

  routing {
    healthRoutes(bridge)
    dashboardRoutes(bridge)
    streamRoute(bridge)
  }
}
