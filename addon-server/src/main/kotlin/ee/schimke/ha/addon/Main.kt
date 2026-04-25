package ee.schimke.ha.addon

import ee.schimke.ha.addon.bridge.HaSupervisorBridge
import ee.schimke.ha.addon.routes.dashboardRoutes
import ee.schimke.ha.addon.routes.healthRoutes
import ee.schimke.ha.addon.routes.streamRoute
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
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger("ee.schimke.ha.addon.Main")

fun main() {
    val cfg = AddonConfig.fromEnv()
    log.info("starting addon-server on :{}, ha={}", cfg.port, cfg.haBaseUrl)

    val bridge = HaSupervisorBridge(cfg.haBaseUrl, cfg.haAccessToken)
    bridge.start()

    val server = embeddedServer(CIO, port = cfg.port, host = "0.0.0.0") {
        configure(bridge)
    }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("shutdown: stopping server + bridge")
            server.stop(1, 5, TimeUnit.SECONDS)
            kotlinx.coroutines.runBlocking { bridge.close() }
        },
    )
    server.start(wait = true)
}

internal fun Application.configure(bridge: HaSupervisorBridge) {
    install(DefaultHeaders) {
        header("X-Powered-By", "ha-remotecompose")
    }
    install(CallLogging)
    install(WebSockets)
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true })
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
