package ee.schimke.ha.addon.routes

import ee.schimke.ha.addon.bridge.HaSupervisorBridge
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * Liveness + readiness for the HA supervisor's healthcheck. Liveness is
 * always 200 once the JVM is up; readiness only flips green when the HA
 * bridge has authenticated and hydrated its state cache.
 */
fun Routing.healthRoutes(bridge: HaSupervisorBridge) {
    get("/healthz") {
        call.respondText("ok", ContentType.Text.Plain)
    }
    get("/readyz") {
        val ready = bridge.state.value == HaSupervisorBridge.ConnectionState.Ready
        if (ready) {
            call.respondText("ready", ContentType.Text.Plain)
        } else {
            call.respondText(
                bridge.state.value.name,
                ContentType.Text.Plain,
                HttpStatusCode.ServiceUnavailable,
            )
        }
    }
}
