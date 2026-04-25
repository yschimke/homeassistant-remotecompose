package ee.schimke.ha.client

import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Config for connecting to a Home Assistant instance over its WebSocket API.
 *
 * HA does **not** expose the resolved dashboard/card JSON over REST. The only way to fetch it is
 * via the WebSocket `lovelace/config` command after auth with an access token. See
 * `homeassistant/components/lovelace/websocket.py`.
 */
data class HaConfig(val baseUrl: String, val accessToken: String)

/**
 * HA WebSocket client — open a connection, authenticate with an access token, run commands, read
 * results. Auth protocol:
 *
 * 1. server → `{type:"auth_required"}`
 * 2. client → `{type:"auth", access_token:"…"}`
 * 3. server → `{type:"auth_ok"}` (or `auth_invalid`)
 * 4. client → `{id:N, type:"<cmd>", …}`
 * 5. server → `{id:N, type:"result", success:true, result:…}`
 *
 * The connection is held open; command IDs are monotonic and responses are matched in a receive
 * loop. Not a subscription client yet — cards work from a point-in-time [HaSnapshot].
 */
class HaClient(private val config: HaConfig) {
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }
  private val httpClient = HttpClient { install(WebSockets) }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val idMutex = Mutex()
  private var nextId = 1
  private val pending = mutableMapOf<Int, CompletableDeferred<JsonObject>>()
  private val pendingMutex = Mutex()
  private var session: DefaultWebSocketSession? = null
  private var receiveJob: Job? = null

  private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
  val state: StateFlow<ConnectionState> = _state.asStateFlow()

  enum class ConnectionState {
    Disconnected,
    Connecting,
    Authenticating,
    Ready,
    Error,
  }

  suspend fun connect() {
    if (session != null) return
    _state.value = ConnectionState.Connecting
    val wsUrl = config.baseUrl.toWsUrl() + "/api/websocket"
    val s = httpClient.webSocketSession { url(wsUrl) }
    session = s

    // Handshake on this coroutine — then spin up the receive loop.
    val authRequired = readMessage(s)
    require(authRequired["type"]?.jsonPrimitive?.content == "auth_required") {
      "expected auth_required, got $authRequired"
    }
    _state.value = ConnectionState.Authenticating
    s.send(
      json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
          put("type", "auth")
          put("access_token", config.accessToken)
        },
      )
    )
    val authResult = readMessage(s)
    when (authResult["type"]?.jsonPrimitive?.content) {
      "auth_ok" -> _state.value = ConnectionState.Ready
      else -> {
        _state.value = ConnectionState.Error
        throw IllegalStateException("auth rejected: $authResult")
      }
    }

    receiveJob = scope.launch { receiveLoop(s) }
  }

  suspend fun fetchDashboard(urlPath: String? = null): Dashboard {
    val result = runCommand("lovelace/config") { if (urlPath != null) put("url_path", urlPath) }
    return json.decodeFromJsonElement(Dashboard.serializer(), result)
  }

  /** Top-level Lovelace dashboards registered in `lovelace/dashboards`. */
  suspend fun listDashboards(): List<DashboardSummary> {
    val result = runCommandArray("lovelace/dashboards/list")
    return result.map {
      val obj = it.jsonObject
      DashboardSummary(
        urlPath = obj["url_path"]?.jsonPrimitive?.content,
        title = obj["title"]?.jsonPrimitive?.content ?: "(untitled)",
        icon = obj["icon"]?.jsonPrimitive?.content,
      )
    }
  }

  suspend fun fetchStates(): Map<String, EntityState> {
    val arr = runCommandArray("get_states")
    return arr
      .associate { el ->
        val obj = el.jsonObject
        val id = obj["entity_id"]?.jsonPrimitive?.content ?: return@associate "" to placeholder()
        id to json.decodeFromJsonElement(EntityState.serializer(), camelizeState(obj))
      }
      .filterKeys { it.isNotEmpty() }
  }

  suspend fun snapshot(): HaSnapshot = HaSnapshot(states = fetchStates())

  suspend fun close() {
    receiveJob?.cancel()
    runCatching { session?.close(CloseReason(CloseReason.Codes.NORMAL, "bye")) }
    session = null
    scope.cancel()
    httpClient.close()
    _state.value = ConnectionState.Disconnected
  }

  private suspend fun runCommand(
    type: String,
    extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
  ): JsonObject {
    val response = awaitCommand(type, extra)
    return response["result"]?.jsonObject ?: JsonObject(emptyMap())
  }

  private suspend fun runCommandArray(
    type: String,
    extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
  ): JsonArray {
    val response = awaitCommand(type, extra)
    return response["result"]?.jsonArray ?: JsonArray(emptyList())
  }

  private suspend fun awaitCommand(
    type: String,
    extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
  ): JsonObject {
    val s = session ?: error("Not connected — call connect() first")
    val id = idMutex.withLock { nextId++ }
    val deferred = CompletableDeferred<JsonObject>()
    pendingMutex.withLock { pending[id] = deferred }
    val msg = buildJsonObject {
      put("id", id)
      put("type", type)
      extra()
    }
    s.send(json.encodeToString(JsonObject.serializer(), msg))
    return withTimeout(30_000) {
      val resp = deferred.await()
      val success = resp["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
      if (!success) {
        val err = resp["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "unknown"
        error("HA command $type failed: $err")
      }
      resp
    }
  }

  private suspend fun receiveLoop(s: DefaultWebSocketSession) {
    try {
      for (frame in s.incoming) {
        val text = (frame as? Frame.Text)?.readText() ?: continue
        val obj = json.parseToJsonElement(text).jsonObject
        val id = obj["id"]?.jsonPrimitive?.intOrNullSafe() ?: continue
        pendingMutex.withLock { pending.remove(id) }?.complete(obj)
      }
    } catch (t: Throwable) {
      pendingMutex.withLock {
        pending.values.forEach { it.completeExceptionally(t) }
        pending.clear()
      }
      _state.value = ConnectionState.Error
    }
  }

  private suspend fun readMessage(s: DefaultWebSocketSession): JsonObject {
    val frame = s.incoming.receive() as? Frame.Text ?: error("expected text frame")
    return json.parseToJsonElement(frame.readText()).jsonObject
  }

  private fun placeholder(): EntityState = EntityState(entityId = "", state = "")

  /**
   * HA returns snake_case keys; our `EntityState` uses camelCase fields. We configure Json with
   * `ignoreUnknownKeys`, so the simplest fix is to rewrite the two keys we care about.
   */
  private fun camelizeState(obj: JsonObject): JsonObject = buildJsonObject {
    for ((k, v) in obj) {
      when (k) {
        "entity_id" -> put("entityId", v)
        "last_changed" -> put("lastChanged", v)
        "last_updated" -> put("lastUpdated", v)
        else -> put(k, v)
      }
    }
  }
}

private fun String.intOrNullSafe(): Int? = this.toIntOrNull()

private fun kotlinx.serialization.json.JsonPrimitive.intOrNullSafe(): Int? = content.toIntOrNull()

/** Pick `ws` / `wss` from `http` / `https`. */
private fun String.toWsUrl(): String =
  when {
    startsWith("https://") -> "wss://" + removePrefix("https://").removeSuffix("/")
    startsWith("http://") -> "ws://" + removePrefix("http://").removeSuffix("/")
    else -> "ws://" + removeSuffix("/")
  }

@Serializable
data class DashboardSummary(val urlPath: String?, val title: String, val icon: String? = null)
