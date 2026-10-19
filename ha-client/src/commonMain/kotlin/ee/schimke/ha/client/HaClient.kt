package ee.schimke.ha.client

import ee.schimke.ha.design.DeviceInfo
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
class HaClient(
  private val config: HaConfig,
  engine: HttpClientEngine? = null,
  private val accessTokenProvider: (suspend () -> String)? = null,
) {
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

  // In-flight one-shot commands, each tagged with the socket that issued
  // it. The tag lets teardown fail only the entries for a dying socket, so
  // an interleaved reconnect — whose pending entries carry a different
  // owner — never has its in-flight commands completed out from under it.
  // Ids are globally monotonic and never reused, so a stale socket's ids
  // can't collide with a replacement's.
  private class Pending(
    val owner: DefaultWebSocketSession,
    val deferred: CompletableDeferred<JsonObject>,
  )

  private val pending = mutableMapOf<Int, Pending>()
  private val pendingMutex = Mutex()

  // Long-lived subscriptions keyed by the same request id as the
  // `subscribe_events` ack. The ack lands in [pending] and is consumed
  // once; subsequent `type:"event"` frames carrying that id are routed
  // here forever (well, until [close]). Separate from [pending] so a
  // subscription's lifecycle isn't tied to the one-shot deferred, and
  // tagged with its owning socket for the same scoped-teardown reason.
  private class Subscription(
    val owner: DefaultWebSocketSession,
    val flow: MutableSharedFlow<JsonObject>,
  )

  private val subscriptions = mutableMapOf<Int, Subscription>()
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

  /**
   * How long opening the socket and the auth exchange may take. Without a bound, a proxy or captive
   * portal that accepts the upgrade but never speaks would hold [sessionMutex] — and every later
   * command, [disconnect] and [close] — forever, with [state] stuck at Connecting.
   */
  internal var handshakeTimeout: Duration = 30.seconds

  suspend fun connect() {
    sessionMutex.withLock {
      if (session != null) return
      _state.value = ConnectionState.Connecting
      var socket: DefaultWebSocketSession? = null
      try {
        val accessToken = accessTokenProvider?.invoke() ?: config.accessToken
        val wsUrl = config.baseUrl.toWsUrl() + "/api/websocket"
        withTimeout(handshakeTimeout) {
          val s = httpClient.webSocketSession { url(wsUrl) }.also { socket = it }
          handshake(s, accessToken)
        }
      } catch (t: Throwable) {
        // Leave no half-open socket behind and no state that reads as "still connecting".
        _state.value =
          if (t is CancellationException && t !is TimeoutCancellationException) {
            ConnectionState.Disconnected
          } else {
            ConnectionState.Error
          }
        socket?.let { s ->
          withContext(NonCancellable) {
            runCatching { s.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "handshake")) }
          }
        }
        // A timeout is a failed connect, not a cancellation of the caller: reconnect loops rethrow
        // CancellationException and would stop retrying.
        if (t is TimeoutCancellationException) {
          throw IllegalStateException("Home Assistant did not answer within $handshakeTimeout", t)
        }
        throw t
      }

      val s = checkNotNull(socket)
      // Publish the socket before Ready: collectors that react to Ready by subscribing (the
      // `state_changed` feed, notifications) would otherwise race this assignment and fail with
      // "Not connected", leaving that connection without its subscription. A socket dying right
      // away can't overwrite Ready backwards: receiveLoop's teardown waits for [sessionMutex].
      session = s
      receiveJob = scope.launch { receiveLoop(s) }
      _state.value = ConnectionState.Ready
    }
  }

  private suspend fun handshake(s: DefaultWebSocketSession, accessToken: String) {
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
      throw HaAuthInvalidException("auth rejected: $authResult")
    }
  }

  suspend fun fetchDashboard(urlPath: String? = null): Dashboard {
    val result = fetchDashboardConfig(urlPath)
    if (!result.hasRenderableViews()) throw UnsupportedDashboardException()
    return json.decodeFromJsonElement(Dashboard.serializer(), result)
  }

  /**
   * A dashboard's Lovelace config exactly as Home Assistant stores it (`lovelace/config`): what an
   * agent edits and [replaceDashboardConfig] writes back. A null [urlPath] is the default
   * dashboard.
   */
  suspend fun fetchDashboardConfig(urlPath: String? = null): JsonObject =
    runCommand("lovelace/config") { if (urlPath != null) put("url_path", urlPath) }

  /**
   * Replace a dashboard's Lovelace config with [config] (`lovelace/config/save`) and return the
   * config it replaced, so the caller can offer undo by saving that back. Home Assistant stays the
   * source of truth: it refuses a YAML-mode dashboard or a user without admin rights, and its
   * message comes back as the thrown error.
   */
  suspend fun replaceDashboardConfig(config: JsonObject, urlPath: String? = null): JsonObject {
    val previous = fetchDashboardConfig(urlPath)
    // The save answers with a null result, which runCommand would read as an object.
    awaitCommand("lovelace/config/save") {
      put("config", config)
      if (urlPath != null) put("url_path", urlPath)
    }
    return previous
  }

  private val dashboardWriteMutex = Mutex()

  /**
   * Check the loaded baseline immediately before saving. HA has no compare-and-swap/revision
   * argument, so this detects observed conflicts; an external writer can still race this pair of
   * WebSocket commands. Serialize this client's writes, including undo, through this method.
   */
  suspend fun compareAndReplaceDashboardConfig(
    expected: JsonObject,
    config: JsonObject,
    urlPath: String? = null,
  ) = dashboardWriteMutex.withLock {
    if (fetchDashboardConfig(urlPath) != expected)
      throw ee.schimke.ha.client.dashboard.DashboardConflictException()
    awaitCommand("lovelace/config/save") {
      put("config", config)
      if (urlPath != null) put("url_path", urlPath)
    }
    Unit
  }

  /**
   * Fetch the HA instance's `config` payload — same shape `GET /api/config` returns over REST,
   * exposed here over the already-open WebSocket so we don't pay a separate TCP/TLS handshake. Used
   * to pick up the user's configured `external_url` (Nabu Casa / reverse-proxy hostname) so the app
   * can route over the public URL when away from the LAN.
   */
  suspend fun fetchConfig(): HaInstanceConfig = instanceConfigOf(runCommand("get_config"))

  /**
   * The device behind each entity, for device-specific card designs. The entity registry's display
   * list (`config/entity_registry/list_for_display`, open to every user) gives each entity's
   * integration, translation key, display precision (`dp`) and device id; the device registry
   * (`config/device_registry/list`) adds manufacturer and model. Without the device registry,
   * entities keep what the first gives, the device id included: that is what lets a card configured
   * by device (a Bambu Lab printer) find its entities for every user.
   */
  suspend fun fetchDevices(): Map<String, DeviceInfo> {
    val entities =
      runCommand("config/entity_registry/list_for_display")["entities"] as? JsonArray
        ?: return emptyMap()
    val devices = runCatching {
      runCommandArray("config/device_registry/list")
    }
      .getOrDefault(JsonArray(emptyList()))
      .mapNotNull { it as? JsonObject }
      .associateBy { it["id"]?.jsonPrimitive?.contentOrNull }
    return entities
      .mapNotNull { it as? JsonObject }
      .mapNotNull { entity ->
        val id = entity["ei"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val deviceId = entity["di"]?.jsonPrimitive?.contentOrNull
        val device = deviceId?.let { devices[it] }
        val info =
          DeviceInfo(
            integration = entity["pl"]?.jsonPrimitive?.contentOrNull,
            manufacturer = device?.get("manufacturer")?.jsonPrimitive?.contentOrNull,
            model = device?.get("model")?.jsonPrimitive?.contentOrNull,
            translationKey = entity["tk"]?.jsonPrimitive?.contentOrNull,
            displayPrecision = entity["dp"]?.jsonPrimitive?.intOrNull,
            deviceId = deviceId,
          )
        (id to info).takeIf { info != DeviceInfo() }
      }
      .toMap()
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
   * [snapshot] with the device registry ([fetchDevices]), what a card configured by device (a Bambu
   * Lab printer) resolves through. A registry that can't be read leaves it out.
   */
  suspend fun snapshotWithDevices(): HaSnapshot = coroutineScope {
    // The unit system is read alongside the states, never in front of them.
    val unit = async { fetchTemperatureUnit() }
    val devices =
      try {
        fetchDevices()
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        emptyMap()
      }
    snapshot().copy(devices = devices, temperatureUnit = unit.await())
  }

  /**
   * Home Assistant's configured temperature unit ([HaInstanceConfig.temperatureUnit]), what climate
   * cards show their temperatures in. Best effort and bounded by [timeout]: null when `get_config`
   * fails, is refused (a proxy that allows only some commands) or doesn't answer in time, and cards
   * fall back to the entity's own unit, then °C. Only the caller's own cancellation propagates.
   */
  suspend fun fetchTemperatureUnit(timeout: Duration = CONFIG_TIMEOUT): String? =
    try {
      withTimeoutOrNull(timeout) { fetchConfig().temperatureUnit }
    } catch (e: CancellationException) {
      // A timeout inside the command is not the caller's cancellation: only rethrow that.
      currentCoroutineContext().ensureActive()
      null
    } catch (e: Exception) {
      null
    }

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
    subscriptionsMutex.withLock { subscriptions[id] = Subscription(s, flow) }
    val deferred = CompletableDeferred<JsonObject>()
    pendingMutex.withLock { pending[id] = Pending(s, deferred) }
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

  /** Authenticated, entity-scoped conversation history; message text stays on the HA connection. */
  suspend fun fetchLogbook(entityId: String, startTime: Instant): JsonArray =
    runCommandArray("logbook/get_events") {
      put("start_time", startTime.toString())
      put("entity_ids", buildJsonArray { add(entityId) })
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

  /**
   * Dismiss a single persistent notification by id — the equivalent of tapping the X next to one
   * entry behind HA's frontend bell. Routed through the `persistent_notification.dismiss` service;
   * HA then fires `persistent_notifications_updated` so any active subscription refetches the
   * now-shorter list.
   */
  suspend fun dismissNotification(notificationId: String) {
    callService(
      domain = "persistent_notification",
      service = "dismiss",
      serviceData = buildJsonObject { put("notification_id", notificationId) },
    )
  }

  /**
   * Dismiss every persistent notification at once — HA's `persistent_notification.dismiss_all`
   * service, the "clear all" affordance behind the frontend bell.
   */
  suspend fun dismissAllNotifications() {
    callService(domain = "persistent_notification", service = "dismiss_all")
  }

  /**
   * Gracefully tear down the live WebSocket but keep the client reusable — unlike [close], which
   * also cancels the client scope and the HTTP engine. Used when the app goes to the background: we
   * close the socket with a NORMAL reason and reset to [ConnectionState.Disconnected], ready for a
   * fresh [connect] when the app returns to the foreground. Cancelling [receiveJob] also runs its
   * cleanup (failing in-flight commands, clearing subscriptions), same as a peer-side close.
   */
  suspend fun disconnect() {
    val s = sessionMutex.withLock {
      val current = session
      session = null
      receiveJob?.cancel()
      receiveJob = null
      current
    }
    runCatching { s?.close(CloseReason(CloseReason.Codes.NORMAL, "background")) }
    // Owner-scoped cleanup: fail only this socket's in-flight commands so
    // callers don't hang to the 30s timeout, and drop its subscriptions.
    // A fresh [connect] re-subscribes with new ids.
    if (s != null) failPending(s, IllegalStateException("disconnected"))
    // Publish Disconnected only if no replacement [connect] slipped in
    // while [s] was closing (it could have grabbed [sessionMutex] the
    // moment we released it above and already be Connecting/Ready). Without
    // this guard we'd clobber the replacement's fresh state.
    sessionMutex.withLock { if (session == null) _state.value = ConnectionState.Disconnected }
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
    // Fail this socket's in-flight commands before tearing the scope down,
    // so awaiting callers fail fast instead of hanging on a cancelled
    // receive loop that can't run its own cleanup.
    if (s != null) failPending(s, IllegalStateException("closed"))
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
    val s = session ?: throw CommandNotSentException("Not connected — call connect() first")
    val id = idMutex.withLock { nextId++ }
    val deferred = CompletableDeferred<JsonObject>()
    pendingMutex.withLock { pending[id] = Pending(s, deferred) }
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
        val live =
          session
            ?: throw CommandNotSentException(
              "Not connected — socket closed before send (cmd=$type)"
            )
        if (live !== s) throw CommandNotSentException("Connection replaced before send (cmd=$type)")
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
            val flow = subscriptionsMutex.withLock { subscriptions[id]?.flow }
            flow?.tryEmit(obj["event"]?.jsonObject ?: continue)
          }
          // Everything else (result, pong, …) is a one-shot response.
          else -> pendingMutex.withLock { pending.remove(id) }?.deferred?.complete(obj)
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
    // Fail/drop only *this* socket's in-flight commands and subscriptions
    // (see [failPending]). This is owner-scoped, so it runs safely even if
    // a newer [connect] already swapped in a replacement socket — the
    // replacement's entries carry a different owner and are left intact.
    failPending(s, cause)
    // The connection state, however, is shared: only the still-current
    // owner may null the live ref and publish Disconnected. If a newer
    // socket has taken over (a quick background→foreground bounce, where a
    // cancelled loop can still reach here via the mutex fast path), leave
    // its Ready state alone.
    sessionMutex.withLock {
      if (session === s) {
        session = null
        _state.value = ConnectionState.Disconnected
      }
    }
  }

  /**
   * Fail every in-flight command and drop every subscription owned by socket [s], leaving entries
   * owned by any other (replacement) socket untouched. Pending callers see [cause] immediately
   * instead of waiting out the 30s command timeout; subscription flows stop emitting and a fresh
   * [connect] re-subscribes with new ids. Safe to call more than once — already-removed entries are
   * simply absent.
   */
  private suspend fun failPending(s: DefaultWebSocketSession, cause: Throwable) {
    pendingMutex.withLock {
      val iter = pending.entries.iterator()
      while (iter.hasNext()) {
        val entry = iter.next()
        if (entry.value.owner === s) {
          entry.value.deferred.completeExceptionally(cause)
          iter.remove()
        }
      }
    }
    subscriptionsMutex.withLock { subscriptions.entries.removeAll { it.value.owner === s } }
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

/**
 * Home Assistant refused the access token in the WebSocket handshake (`auth_invalid`): it expired
 * or was revoked. Reconnecting with the same token can't succeed; a caller holding a refresh token
 * mints a new one (the [HaClient] `accessTokenProvider` is asked again on every connect).
 */
class HaAuthInvalidException(message: String) : IllegalStateException(message)

@Serializable
data class DashboardSummary(val urlPath: String?, val title: String, val icon: String? = null)

/** How long [HaClient.fetchTemperatureUnit] waits for `get_config` before going on without it. */
private val CONFIG_TIMEOUT: Duration = 3.seconds

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
  /** `unit_system.temperature`: `°C` or `°F`, the unit climate temperatures are reported in. */
  val temperatureUnit: String? = null,
)

/** Read a `get_config` result (the same shape as REST `GET /api/config`). */
internal fun instanceConfigOf(result: JsonObject): HaInstanceConfig =
  HaInstanceConfig(
    version = result["version"]?.jsonPrimitive?.content,
    locationName = result["location_name"]?.jsonPrimitive?.content,
    internalUrl = result["internal_url"]?.nullableString(),
    externalUrl = result["external_url"]?.nullableString(),
    temperatureUnit =
      ((result["unit_system"] as? JsonObject)?.get("temperature"))?.nullableString()?.takeIf {
        it.isNotBlank()
      },
  )

private fun kotlinx.serialization.json.JsonElement.nullableString(): String? =
  when (this) {
    is kotlinx.serialization.json.JsonNull -> null
    is kotlinx.serialization.json.JsonPrimitive -> content
    else -> null
  }

/**
 * A command refused before it was written to the socket (none open, or it closed or was replaced
 * first): Home Assistant never saw it, so it is safe to send again on another connection. Any other
 * failure may have reached Home Assistant.
 */
class CommandNotSentException(message: String) : IllegalStateException(message)
