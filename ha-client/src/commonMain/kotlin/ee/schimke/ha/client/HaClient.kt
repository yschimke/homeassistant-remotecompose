package ee.schimke.ha.client

import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaNotification
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.HistoryPoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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
class HaClient(private val config: HaConfig, engine: HttpClientEngine? = null) {
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }
  private val httpClient =
    if (engine != null) {
      HttpClient(engine) { install(WebSockets) }
    } else {
      HttpClient { install(WebSockets) }
    }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val idMutex = Mutex()
  private var nextId = 1
  private val pending = mutableMapOf<Int, CompletableDeferred<JsonObject>>()
  private val pendingMutex = Mutex()
  // Long-lived subscriptions keyed by the same request id as the
  // `subscribe_events` ack. The ack lands in [pending] and is consumed
  // once; subsequent `type:"event"` frames carrying that id are routed
  // here forever (well, until [close]). Separate from [pending] so a
  // subscription's lifecycle isn't tied to the one-shot deferred.
  private val subscriptions = mutableMapOf<Int, MutableSharedFlow<JsonObject>>()
  private val subscriptionsMutex = Mutex()
  // Guards mutation of [session] / [receiveJob] and serialises [send] so two
  // concurrent commands can't interleave frames on the same socket.
  private val sessionMutex = Mutex()
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
    sessionMutex.withLock {
      if (session != null) return
      _state.value = ConnectionState.Connecting
      val wsUrl = config.baseUrl.toWsUrl() + "/api/websocket"
      val s = httpClient.webSocketSession { url(wsUrl) }

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
          runCatching { s.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "auth")) }
          throw IllegalStateException("auth rejected: $authResult")
        }
      }

      session = s
      receiveJob = scope.launch { receiveLoop(s) }
    }
  }

  suspend fun fetchDashboard(urlPath: String? = null): Dashboard {
    val result = runCommand("lovelace/config") { if (urlPath != null) put("url_path", urlPath) }
    return json.decodeFromJsonElement(Dashboard.serializer(), result)
  }

  /**
   * Fetch the HA instance's `config` payload — same shape `GET /api/config` returns over REST,
   * exposed here over the already-open WebSocket so we don't pay a separate TCP/TLS handshake. Used
   * to pick up the user's configured `external_url` (Nabu Casa / reverse-proxy hostname) so the app
   * can route over the public URL when away from the LAN.
   */
  suspend fun fetchConfig(): HaInstanceConfig {
    val result = runCommand("get_config")
    return HaInstanceConfig(
      version = result["version"]?.jsonPrimitive?.content,
      locationName = result["location_name"]?.jsonPrimitive?.content,
      internalUrl = result["internal_url"]?.nullableString(),
      externalUrl = result["external_url"]?.nullableString(),
    )
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

  /**
   * Call a Home Assistant service (`call_service` WebSocket command).
   *
   * Mirrors the shape Lovelace tap actions produce — a `domain.service` targeting an optional
   * entity, with arbitrary extra data. The dashboard-side `HaActionDispatcher` decodes button taps
   * into [HaAction.CallService][ee.schimke.ha.rc.components.HaAction.CallService] payloads and
   * routes them here.
   *
   * Returns when HA acknowledges the command. Errors propagate as exceptions out of `awaitCommand`
   * so callers can surface them.
   */
  suspend fun callService(
    domain: String,
    service: String,
    entityId: String? = null,
    serviceData: JsonObject = JsonObject(emptyMap()),
  ) {
    runCommand("call_service") {
      put("domain", domain)
      put("service", service)
      if (serviceData.isNotEmpty()) put("service_data", serviceData)
      if (entityId != null) {
        put("target", buildJsonObject { put("entity_id", entityId) })
      }
    }
  }

  /**
   * Subscribe to a Home Assistant event type and stream every matching event over the returned
   * [SharedFlow]. Cold callers see only events that arrive after they start collecting; nothing is
   * buffered for late subscribers beyond a small backlog so a brief composition gap doesn't drop
   * the newest emit.
   *
   * The subscription lives until [close] (or until the socket dies and the receive loop clears the
   * registry). HA's `unsubscribe_events` command isn't surfaced — callers either care for the
   * session's lifetime or not at all.
   */
  suspend fun subscribeEvents(eventType: String): SharedFlow<JsonObject> {
    val s = session ?: error("Not connected — call connect() first")
    val id = idMutex.withLock { nextId++ }
    val flow =
      MutableSharedFlow<JsonObject>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
      )
    subscriptionsMutex.withLock { subscriptions[id] = flow }
    val deferred = CompletableDeferred<JsonObject>()
    pendingMutex.withLock { pending[id] = deferred }
    val msg = buildJsonObject {
      put("id", id)
      put("type", "subscribe_events")
      put("event_type", eventType)
    }
    try {
      sessionMutex.withLock {
        val live = session ?: error("Not connected — socket closed before subscribe")
        if (live !== s) error("Connection replaced before subscribe")
        live.send(json.encodeToString(JsonObject.serializer(), msg))
      }
      val resp = withTimeout(30_000) { deferred.await() }
      val success = resp["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
      if (!success) {
        val err = resp["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "unknown"
        subscriptionsMutex.withLock { subscriptions.remove(id) }
        error("HA subscribe_events($eventType) failed: $err")
      }
      return flow.asSharedFlow()
    } catch (t: Throwable) {
      subscriptionsMutex.withLock { subscriptions.remove(id) }
      throw t
    } finally {
      pendingMutex.withLock { pending.remove(id) }
    }
  }

  /**
   * Fetch the current set of HA persistent notifications — the things behind the frontend's bell
   * icon. One-shot; pair with [subscribeEvents]`("persistent_notifications_updated")` for live
   * updates (HA's update event is empty, so refetching is the only way to learn the new contents).
   */
  suspend fun fetchPersistentNotifications(): List<HaNotification> {
    val arr = runCommandArray("persistent_notification/get")
    return arr.map { el ->
      val obj = el.jsonObject
      HaNotification(
        notificationId = obj["notification_id"]?.jsonPrimitive?.content ?: "",
        title = obj["title"]?.nullableString(),
        message = obj["message"]?.nullableString() ?: "",
        createdAt = obj["created_at"]?.nullableString(),
      )
    }
  }

  /**
   * Fetch recorder history for [entityIds] over `[startTime, endTime]` via HA's
   * `history/history_during_period` WebSocket command. The result is keyed by entity id; each value
   * is the entity's state changes in chronological order.
   *
   * Requested with `minimal_response` + `no_attributes` so the payload is just `{ s: <state>, lu:
   * <last-updated epoch seconds> }` per point — enough to draw a sparkline / state timeline without
   * hauling every attribute change across the socket. `significant_changes_only` is left off so
   * on/off entities keep every flip (otherwise HA elides the intermediate transitions a timeline
   * needs).
   */
  suspend fun fetchHistory(
    entityIds: List<String>,
    startTime: Instant,
    endTime: Instant? = null,
  ): Map<String, List<HistoryPoint>> {
    if (entityIds.isEmpty()) return emptyMap()
    val result =
      runCommand("history/history_during_period") {
        put("start_time", startTime.toString())
        if (endTime != null) put("end_time", endTime.toString())
        put("entity_ids", buildJsonArray { entityIds.forEach { add(it) } })
        put("minimal_response", true)
        put("no_attributes", true)
        put("significant_changes_only", false)
      }
    return result.mapValues { (_, points) ->
      (points as? JsonArray ?: JsonArray(emptyList())).mapNotNull { parseHistoryPoint(it) }
    }
  }

  /**
   * Decode one compressed history entry. HA emits `s` for the state and a float epoch-seconds
   * timestamp under `lu` (last updated), falling back to `lc` (last changed) on the first sample of
   * a run. Anything missing a state or timestamp is dropped rather than guessed.
   */
  private fun parseHistoryPoint(element: kotlinx.serialization.json.JsonElement): HistoryPoint? {
    val obj = element as? JsonObject ?: return null
    val state = obj["s"]?.jsonPrimitive?.content ?: return null
    val seconds = (obj["lu"] ?: obj["lc"])?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
    return HistoryPoint(
      ts = Instant.fromEpochMilliseconds((seconds * 1000).toLong()),
      state = state,
    )
  }

  suspend fun close() {
    val s = sessionMutex.withLock {
      val current = session
      session = null
      receiveJob?.cancel()
      receiveJob = null
      current
    }
    runCatching { s?.close(CloseReason(CloseReason.Codes.NORMAL, "bye")) }
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
    try {
      // Serialise WebSocket writes: Ktor's send is a suspend over a single
      // outgoing channel, but cancelling one sender mid-encode while
      // another tries to send produces interleaved/corrupted frames the
      // server will close on. Holding [sessionMutex] also pins the
      // current socket across the write so a reconnect can't swap it
      // out under us.
      sessionMutex.withLock {
        val live = session ?: error("Not connected — socket closed before send (cmd=$type)")
        if (live !== s) error("Connection replaced before send (cmd=$type)")
        live.send(json.encodeToString(JsonObject.serializer(), msg))
      }
      return withTimeout(30_000) {
        val resp = deferred.await()
        val success = resp["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        if (!success) {
          val err = resp["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "unknown"
          error("HA command $type failed: $err")
        }
        resp
      }
    } finally {
      // Always remove our pending entry — covers normal completion,
      // timeout, host-side cancellation (dashboard switch), and send
      // failures. Leaking deferreds slowly grew the map on every reload.
      pendingMutex.withLock { pending.remove(id) }
    }
  }

  private suspend fun receiveLoop(s: DefaultWebSocketSession) {
    var failure: Throwable? = null
    try {
      for (frame in s.incoming) {
        val text = (frame as? Frame.Text)?.readText() ?: continue
        val obj = json.parseToJsonElement(text).jsonObject
        val id = obj["id"]?.jsonPrimitive?.intOrNullSafe() ?: continue
        when (obj["type"]?.jsonPrimitive?.content) {
          // `subscribe_events` streams `type:"event"` frames sharing the
          // subscription's request id. Route them to the live flow; do
          // NOT touch [pending] (the ack already consumed that slot).
          "event" -> {
            val flow = subscriptionsMutex.withLock { subscriptions[id] }
            flow?.tryEmit(obj["event"]?.jsonObject ?: continue)
          }
          // Everything else (result, pong, …) is a one-shot response.
          else -> pendingMutex.withLock { pending.remove(id) }?.complete(obj)
        }
      }
    } catch (t: Throwable) {
      failure = t
    }
    // The socket is gone — either the server closed `incoming` cleanly
    // or we caught a throwable. Either way, the connection isn't usable
    // anymore: drop our reference so the next [connect] reopens it
    // instead of routing fresh `awaitCommand`s into a dead socket
    // (which surfaces as `SocketException: Software caused connection
    // abort` on the next send). Also fail any in-flight deferreds so
    // their callers see a clear cause instead of timing out at 30s.
    val cause = failure ?: IllegalStateException("WebSocket closed by peer")
    pendingMutex.withLock {
      pending.values.forEach { it.completeExceptionally(cause) }
      pending.clear()
    }
    // Drop subscription flows too: the server-side subscriptions died
    // with the socket, so future events would never land. Callers
    // observing the flow stop seeing emissions; HaSession's reconnect
    // will re-subscribe with a fresh id.
    subscriptionsMutex.withLock { subscriptions.clear() }
    sessionMutex.withLock { if (session === s) session = null }
    _state.value = ConnectionState.Disconnected
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

/**
 * Subset of HA's `config` payload we care about. HA's `external_url` is set when the user has
 * configured remote access (Nabu Casa, reverse proxy, etc.); `internal_url` is what the user
 * configured for LAN access. Either may be null if the user hasn't set them.
 */
@Serializable
data class HaInstanceConfig(
  val version: String? = null,
  val locationName: String? = null,
  val internalUrl: String? = null,
  val externalUrl: String? = null,
)

private fun kotlinx.serialization.json.JsonElement.nullableString(): String? =
  when (this) {
    is kotlinx.serialization.json.JsonNull -> null
    is kotlinx.serialization.json.JsonPrimitive -> content
    else -> null
  }
