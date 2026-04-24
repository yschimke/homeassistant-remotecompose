package ee.schimke.ha.addon.routes

import ee.schimke.ha.addon.bridge.HaSupervisorBridge
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.EntityState
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

/**
 * Public REST surface, version-pinned at /v1.
 *
 * - `/v1/dashboards` — list registered Lovelace dashboards
 * - `/v1/dashboards/{path}` — resolved dashboard config
 * - `/v1/snapshot` — current state cache (debug + fallback for clients
 *   that don't speak the WS stream yet)
 *
 * The `.rc`-byte endpoint is intentionally omitted at this milestone —
 * see PLAN.md (M3). Card rendering on the JVM lands once the
 * `rc-converter-jvm` port exists.
 */
fun Routing.dashboardRoutes(bridge: HaSupervisorBridge) {
    route("/v1") {
        get("/dashboards") {
            val arr: JsonArray = bridge.listDashboards()
            call.respond(arr)
        }
        get("/dashboards/{path}") {
            val path = call.parameters["path"]
            val dashboard: Dashboard = bridge.fetchDashboard(path)
            call.respond(dashboard)
        }
        get("/snapshot") {
            call.respond(SnapshotResponse(bridge.cache.states.value))
        }
        get("/cards/{cardId}.rc") {
            // M3 — encoder lives in `rc-converter-jvm`, not yet wired.
            // Returning a typed 501 lets clients surface an "unsupported
            // server build" rather than a generic transport error.
            call.respond(
                HttpStatusCode.NotImplemented,
                mapOf("error" to "rc encoder not yet available in this build"),
            )
        }
    }
}

@Serializable
private data class SnapshotResponse(val states: Map<String, EntityState>)
