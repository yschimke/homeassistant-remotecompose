package ee.schimke.ha.client

import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Config for connecting to a Home Assistant instance over its WebSocket API.
 *
 * HA does **not** expose the resolved dashboard/card JSON over REST. The only
 * way to fetch it is via the WebSocket `lovelace/config` command after auth
 * with a long-lived access token. See
 * `homeassistant/components/lovelace/websocket.py`.
 */
data class HaConfig(
    val host: String,
    val port: Int = 8123,
    val secure: Boolean = false,
    val accessToken: String,
)

/**
 * Minimal HA WebSocket client — just enough to pull a dashboard config and
 * an initial state snapshot. Subscriptions (state-changed, statistics,
 * history stream) are out of scope for the first pass; converters work from
 * a point-in-time [HaSnapshot].
 */
class HaClient(private val config: HaConfig) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = HttpClient { install(WebSockets) }
    private val idMutex = Mutex()
    private var nextId = 1

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    suspend fun fetchDashboard(urlPath: String? = null): Dashboard {
        val body = runCommand("lovelace/config") {
            put("url_path", urlPath)
        }
        return json.decodeFromJsonElement(Dashboard.serializer(), body)
    }

    suspend fun fetchStates(): Map<String, EntityState> {
        TODO("wire get_states command — returns array of {entity_id, state, attributes, last_changed, last_updated}")
    }

    suspend fun snapshot(): HaSnapshot = HaSnapshot(states = fetchStates())

    private suspend fun runCommand(
        type: String,
        extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = {},
    ): JsonObject {
        // Sketch only — replaces with a persistent connection + request/response
        // map keyed by message id. Leaving as a single-shot call so the shape
        // is visible at review time.
        TODO("open ws://$config/api/websocket, auth with accessToken, send $type with id, await result")
    }

    enum class ConnectionState { Disconnected, Connecting, Authenticating, Ready, Error }
}

@Serializable
private data class Incoming(val id: Int? = null, val type: String, val success: Boolean? = null, val result: JsonObject? = null)
