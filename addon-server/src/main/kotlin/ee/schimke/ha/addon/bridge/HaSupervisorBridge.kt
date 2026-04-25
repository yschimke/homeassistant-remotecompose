package ee.schimke.ha.addon.bridge

import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.EntityState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * One long-lived WebSocket session to the Home Assistant Core API. Owns:
 *
 * - auth handshake (`SUPERVISOR_TOKEN` when running as an HA add-on, or a long-lived access token
 *   for local dev)
 * - request/response commands multiplexed by monotonic `id`
 * - `state_changed` / `lovelace_updated` event subscriptions, materialised as a [StateCache]
 *   (latest per entity) and a [SharedFlow] of raw events
 *
 * This is intentionally a superset of `ha-client`'s [HaClient]: that class is shared with the
 * Android client and only needs request/response; the server also needs long-running event
 * subscriptions, so we open a second kind of connection here rather than widen the shared API.
 *
 * Reconnect policy is exponential-backoff with a cap; on reconnect we re-subscribe and refresh the
 * state snapshot so downstream clients just see a brief gap, not an incoherent cache.
 */
class HaSupervisorBridge(
  private val baseUrl: String,
  private val accessToken: String,
  private val scope: CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("ha-bridge")),
) {
  private val log = LoggerFactory.getLogger(HaSupervisorBridge::class.java)
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }
  private val http = HttpClient(CIO) { install(WebSockets) }

  private val idMutex = Mutex()
  private var nextId = 1
  private val pending = mutableMapOf<Int, CompletableDeferred<JsonObject>>()
  private val pendingMutex = Mutex()

  @Volatile private var session: DefaultWebSocketSession? = null
  private var receiveJob: Job? = null
  private var connectJob: Job? = null

  private val _state = MutableStateFlow(ConnectionState.Disconnected)
  val state: StateFlow<ConnectionState> = _state.asStateFlow()

  val cache = StateCache()

  private val _events = MutableSharedFlow<HaEvent>(replay = 0, extraBufferCapacity = 256)
  val events: SharedFlow<HaEvent> = _events.asSharedFlow()

  enum class ConnectionState {
    Disconnected,
    Connecting,
    Authenticating,
    Ready,
    Error,
  }

  fun start() {
    if (connectJob != null) return
    connectJob = scope.launch { runWithReconnect() }
  }

  suspend fun close() {
    connectJob?.cancel()
    receiveJob?.cancel()
    runCatching { session?.close(CloseReason(CloseReason.Codes.NORMAL, "bye")) }
    session = null
    http.close()
    scope.cancel()
    _state.value = ConnectionState.Disconnected
  }

  /** Snapshot of dashboards registered in the Lovelace storage layer. */
  suspend fun listDashboards(): JsonArray = runCommandArray("lovelace/dashboards/list")

  /**
   * Resolved Lovelace dashboard. `urlPath=null` asks HA for the default dashboard (i.e. the one
   * mounted at `/lovelace`).
   */
  suspend fun fetchDashboard(urlPath: String?): Dashboard {
    val obj = runCommand("lovelace/config") { if (urlPath != null) put("url_path", urlPath) }
    return json.decodeFromJsonElement(Dashboard.serializer(), obj)
  }

  /** Passthrough `call_service` — runs under the supervisor identity. */
  suspend fun callService(
    domain: String,
    service: String,
    entityId: String?,
    data: JsonObject?,
  ): JsonObject =
    runCommand("call_service") {
      put("domain", domain)
      put("service", service)
      if (entityId != null) {
        put("target", buildJsonObject { put("entity_id", entityId) })
      }
      if (data != null) put("service_data", data)
    }

  private suspend fun runWithReconnect() {
    var backoffMs = 1_000L
    while (true) {
      try {
        connectOnce()
        backoffMs = 1_000L
        receiveJob?.join()
      } catch (t: Throwable) {
        log.warn("HA bridge disconnected: {}", t.message)
        _state.value = ConnectionState.Error
      }
      _state.value = ConnectionState.Disconnected
      delay(backoffMs)
      backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
    }
  }

  private suspend fun connectOnce() {
    _state.value = ConnectionState.Connecting
    // Same path whether we hit HA directly (http://ha:8123) or via the
    // supervisor proxy (http://supervisor/core) — the proxy is path-
    // transparent, so `/api/websocket` lands at HA's WS handler in both.
    val wsUrl = baseUrl.toWsUrl() + "/api/websocket"
    val s = http.webSocketSession { url(wsUrl) }
    session = s

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
          put("access_token", accessToken)
        },
      )
    )
    val authResult = readMessage(s)
    if (authResult["type"]?.jsonPrimitive?.content != "auth_ok") {
      error("HA auth rejected: $authResult")
    }
    _state.value = ConnectionState.Ready
    log.info("HA bridge authenticated")

    // Start receive loop before sending subscriptions so their results
    // aren't dropped.
    receiveJob = scope.launch { receiveLoop(s) }

    hydrateStates()
    subscribe("state_changed")
    subscribe("lovelace_updated")
  }

  private suspend fun hydrateStates() {
    val arr = runCommandArray("get_states")
    val map = LinkedHashMap<String, EntityState>(arr.size)
    for (el in arr) {
      val obj = el.jsonObject
      val id = obj["entity_id"]?.jsonPrimitive?.content ?: continue
      map[id] = json.decodeFromJsonElement(EntityState.serializer(), camelizeState(obj))
    }
    cache.replaceAll(map)
    log.info("hydrated {} entities", map.size)
  }

  private suspend fun subscribe(eventType: String): Int {
    val id = idMutex.withLock { nextId++ }
    val deferred = CompletableDeferred<JsonObject>()
    pendingMutex.withLock { pending[id] = deferred }
    val frame =
      json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
          put("id", id)
          put("type", "subscribe_events")
          put("event_type", eventType)
        },
      )
    session!!.send(frame)
    withTimeout(10_000) {
      val resp = deferred.await()
      require(resp["success"]?.jsonPrimitive?.content != "false") {
        "subscribe $eventType failed: $resp"
      }
    }
    return id
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
    val s = session ?: error("HA bridge not connected")
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
        when (obj["type"]?.jsonPrimitive?.content) {
          "event" -> handleEvent(obj["event"]?.jsonObject ?: continue)
          else -> {
            val id = obj["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
            pendingMutex.withLock { pending.remove(id) }?.complete(obj)
          }
        }
      }
    } catch (t: Throwable) {
      pendingMutex.withLock {
        pending.values.forEach { it.completeExceptionally(t) }
        pending.clear()
      }
      _state.value = ConnectionState.Error
    }
  }

  private suspend fun handleEvent(event: JsonObject) {
    val eventType = event["event_type"]?.jsonPrimitive?.content ?: return
    val data = event["data"]?.jsonObject ?: JsonObject(emptyMap())
    when (eventType) {
      "state_changed" -> {
        val entityId = data["entity_id"]?.jsonPrimitive?.content ?: return
        val newState = data["new_state"]?.jsonObject
        if (newState == null) {
          cache.remove(entityId)
        } else {
          val typed = json.decodeFromJsonElement(EntityState.serializer(), camelizeState(newState))
          cache.put(entityId, typed)
        }
        _events.tryEmit(HaEvent.StateChanged(entityId))
      }
      "lovelace_updated" -> {
        val urlPath = data["url_path"]?.jsonPrimitive?.content
        _events.tryEmit(HaEvent.LovelaceUpdated(urlPath))
      }
      else -> Unit
    }
  }

  private suspend fun readMessage(s: DefaultWebSocketSession): JsonObject {
    val frame = s.incoming.receive() as? Frame.Text ?: error("expected text frame")
    return json.parseToJsonElement(frame.readText()).jsonObject
  }

  /** HA returns snake_case; our `EntityState` is camelCase. */
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

  private fun String.toWsUrl(): String =
    when {
      startsWith("https://") -> "wss://" + removePrefix("https://").removeSuffix("/")
      startsWith("http://") -> "ws://" + removePrefix("http://").removeSuffix("/")
      startsWith("wss://") || startsWith("ws://") -> removeSuffix("/")
      else -> "ws://" + removeSuffix("/")
    }
}

sealed interface HaEvent {
  data class StateChanged(val entityId: String) : HaEvent

  data class LovelaceUpdated(val urlPath: String?) : HaEvent
}
