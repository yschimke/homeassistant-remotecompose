package ee.schimke.terrazzo.wearsync

import android.content.Context
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.horologist.data.WearDataLayerRegistry
import ee.schimke.ha.client.design.CardDesignOverrides
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.bambuEntityIds
import ee.schimke.ha.rc.cards.resolveBambuDevices
import ee.schimke.ha.rc.displayPrecisionOf
import ee.schimke.terrazzo.core.pin.MobilePinnedCard
import ee.schimke.terrazzo.core.pin.MobilePinnedSection
import ee.schimke.terrazzo.core.pin.PinStore
import ee.schimke.terrazzo.core.pin.PinnedCardData
import ee.schimke.terrazzo.core.pin.WearWidgetSlot
import ee.schimke.terrazzo.core.pin.WearWidgetSlotsStore
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.SessionConnectionStatus
import ee.schimke.terrazzo.core.wearsync.WearSyncManager
import ee.schimke.terrazzo.dashboard.CardDesignOverrideStore
import ee.schimke.terrazzo.dashboard.CardDesignPreload
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.EntityDelta
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import ee.schimke.terrazzo.wearsync.proto.PinnedCard
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSection
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import ee.schimke.terrazzo.wearsync.proto.StreamUpdate
import ee.schimke.terrazzo.wearsync.proto.WEAR_EXTRA_ATTRIBUTES
import ee.schimke.terrazzo.wearsync.proto.WearDesignBlob
import ee.schimke.terrazzo.wearsync.proto.WearLease
import ee.schimke.terrazzo.wearsync.proto.WearPicture
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import ee.schimke.terrazzo.wearsync.proto.WearSlotDesign
import ee.schimke.terrazzo.wearsync.proto.WearSlotDesigns
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import ee.schimke.terrazzo.wearsync.proto.WearWidgetSlots
import ee.schimke.terrazzo.wearsync.proto.WidgetSlot
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import ee.schimke.terrazzo.widget.WidgetImages
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phone-side sync engine. Mirrors:
 *
 * - [PreferencesStore.demoMode] → `/wear/settings`
 * - The current [HaSession]'s dashboards → `/wear/dashboard/<urlPath>` (one DataItem per dashboard,
 *   on first session-bind)
 * - The current snapshot → `/wear/values` (DataStore-style at the wall refresh interval) **or**
 *   `/wear/stream` (MessageClient ephemeral deltas) when wear holds an active lease
 * - [PinStore.cards] → `/wear/pinned`
 * - [PinStore.sections] → `/wear/sections`
 *
 * Lease is tracked via MessageClient frames at `/wear/lease`: as long as a recent lease arrived (or
 * pinned cards exist) the manager streams deltas at the live cadence; otherwise it batches into the
 * `/wear/values` DataItem so wear reads cold are still useful while keeping radio-time low when the
 * watch isn't engaged.
 *
 * Diagnostics: every write / send updates [MobileSyncStatsStore].
 */
class MobileWearSyncManager(
  private val context: Context,
  private val statsStore: MobileSyncStatsStore,
  /** [url] on `baseUrl` as a Wear widget picture: PNG small enough for one DataItem. */
  private val fetchPicture: suspend (baseUrl: String, url: String) -> ByteArray? = { baseUrl, url ->
    // Fresh: a camera's picture changes behind a URL that may not.
    WidgetImages.png(
      context,
      baseUrl,
      url,
      WEAR_PICTURE_MAX_PX,
      WEAR_PICTURE_MAX_BYTES,
      fresh = true,
    )
  },
) : WearSyncManager {

  private val dataClient: DataClient by lazy { Wearable.getDataClient(context) }
  private val messageClient: MessageClient by lazy { Wearable.getMessageClient(context) }
  private val nodeClient: NodeClient by lazy { Wearable.getNodeClient(context) }

  /** Lifted Horologist registry so future code can use `protoFlow`/`protoDataStore`. */
  @OptIn(com.google.android.horologist.annotations.ExperimentalHorologistApi::class)
  @Suppress("unused")
  private val registry: WearDataLayerRegistry by lazy {
    WearDataLayerRegistry.fromContext(
      application = context.applicationContext as android.app.Application,
      coroutineScope = managerScope,
    )
  }

  private val sessionState: MutableStateFlow<HaSession?> = MutableStateFlow(null)
  private val leaseState: MutableStateFlow<Long> = MutableStateFlow(0L)
  private val wearableAvailableState: MutableStateFlow<Boolean> = MutableStateFlow(true)
  private lateinit var managerScope: CoroutineScope
  private var streamJob: Job? = null
  private var recoveryJob: Job? = null
  private var lastSnapshot: Map<String, EntityValue> = emptyMap()
  private var lastValuesItem: Map<String, EntityValue>? = null

  /**
   * The cards in assigned Wear widget slots, by their pin's `cardKey`: whose pictures and Wear
   * designs the watch needs.
   */
  private val wearSlotCards: MutableStateFlow<List<Pair<String, CardConfig>>> =
    MutableStateFlow(emptyList())

  /** The Wear design blobs written to the Data Layer by this process, by sha256. */
  private val pushedDesigns = HashSet<String>()

  /** The slot designs last written to `/wear/slot-designs`, so an unchanged choice isn't resent. */
  private var lastSlotDesigns: List<WearSlotDesign>? = null

  /**
   * The session's latest snapshot, device registry included. The watch's values carry entity states
   * only, so the cards sent to it are resolved against this first ([resolvedFor]).
   */
  private val registrySnapshot: MutableStateFlow<HaSnapshot> = MutableStateFlow(HaSnapshot())

  /**
   * The entities the cards sent to the watch read, by what sent them: each dashboard (by its
   * DataItem path), the pinned cards and the pinned sections. Only these entities' values go to the
   * watch ([wearValues]): every entity Home Assistant has would outgrow a DataItem.
   */
  private val dashboardEntities = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
  private val pinnedEntities = MutableStateFlow<Set<String>>(emptySet())
  private val sectionEntities = MutableStateFlow<Set<String>>(emptySet())

  /** Every entity a card on the watch reads: see [dashboardEntities]. */
  private val watchEntityIds: Set<String>
    get() =
      dashboardEntities.value.values.flatten().toSet() +
        pinnedEntities.value +
        sectionEntities.value

  /** Per entity: the picture URL last pushed, its PNG's hash, and when. */
  private val pushedPictures = HashMap<String, PushedPicture>()

  private var inputEditingMode: StateFlow<Boolean> = MutableStateFlow(false)
  private val inputCards = MutableStateFlow<Map<String, CardConfig>>(emptyMap())
  private val inputMutex = kotlinx.coroutines.sync.Mutex()
  private val handledInputRequests = linkedSetOf<String>()

  private suspend fun handleInputRequest(event: com.google.android.gms.wearable.MessageEvent) {
    if (event.data.size > 32768) return
    val request =
      runCatching { json.parseToJsonElement(event.data.decodeToString()).jsonObject }.getOrNull()
        ?: return
    val id = request["id"]?.jsonPrimitive?.contentOrNull ?: return
    if (id.length > 100) return
    inputMutex.lock()
    try {
      val outcome = runCatching {
        val session = requireNotNull(sessionState.value) { "Sign in on the phone" }
        require(session !is ee.schimke.terrazzo.core.session.DemoHaSession) {
          "Demo mode is read-only"
        }
        require(handledInputRequests.add(event.sourceNodeId + id)) {
          "This request was already submitted"
        }
        while (handledInputRequests.size > 100) handledInputRequests.remove(
          handledInputRequests.first()
        )
        val raw = request["card"]?.jsonPrimitive?.contentOrNull
        val values =
          (request["values"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        val action =
          wearInputAction(
            raw,
            values,
            inputCards.value,
            registrySnapshot.value,
            readOnly =
              !inputEditingMode.value || session is ee.schimke.terrazzo.core.session.DemoHaSession,
          )
        check(session === sessionState.value) { "Session changed" }
        session.callService(action.domain, action.service, action.entityId, action.serviceData)
      }
      if (outcome.exceptionOrNull() is CancellationException) throw outcome.exceptionOrNull()!!
      val response =
        kotlinx.serialization.json.buildJsonObject {
          put("id", kotlinx.serialization.json.JsonPrimitive(id))
          put("ok", kotlinx.serialization.json.JsonPrimitive(outcome.isSuccess))
          if (outcome.isFailure)
            put(
              "error",
              kotlinx.serialization.json.JsonPrimitive(
                "Message was not sent. Check the phone connection and card choices."
              ),
            )
        }
      messageClient
        .sendMessage(
          event.sourceNodeId,
          "/wear/input-result/v1",
          response.toString().encodeToByteArray(),
        )
        .await()
    } finally {
      inputMutex.unlock()
    }
  }

  /** Gives each watch that asks its own Home Assistant token: see [WatchAccessIssuer]. */
  private val accessIssuer =
    WatchAccessIssuer(
      send = { nodeId, access ->
        messageClient.sendMessage(nodeId, WearSyncPaths.ACCESS_MESSAGE, encodeProto(access)).await()
      },
      nodeName = { nodeId ->
        nodeClient.connectedNodes.await().firstOrNull { it.id == nodeId }?.displayName
      },
    )

  private val leaseListener = MessageClient.OnMessageReceivedListener { event ->
    if (event.path == WearSyncPaths.ACCESS_REQUEST_MESSAGE) {
      managerScope.launch {
        runCatching { accessIssuer.request(event.sourceNodeId, sessionState.value) }
          .onFailure {
            if (it is CancellationException) throw it
            handleFailure("accessRequest", it)
          }
      }
      return@OnMessageReceivedListener
    }
    if (event.path == "/wear/input/v1") {
      managerScope.launch {
        runCatching { handleInputRequest(event) }
          .onFailure {
            if (it is CancellationException) throw it
            handleFailure("inputRequest", it)
          }
      }
      return@OnMessageReceivedListener
    }
    if (event.path != WearSyncPaths.LEASE_MESSAGE) return@OnMessageReceivedListener
    val now = System.currentTimeMillis()
    val lease: WearLease = decodeProto<WearLease>(event.data) ?: WearLease(sentAtMs = now)
    leaseState.value = lease.sentAtMs.takeIf { it > 0 } ?: now
    managerScope.launch { statsStore.recordLease(now) }
  }

  /** Update the active session whenever login/demo state changes. */
  override fun setSession(session: HaSession?) {
    inputCards.value = emptyMap()
    sessionState.value = session
  }

  override suspend fun revokeWatchAccess(session: HaSession) = accessIssuer.revokeAll(session)

  override fun clearWatchData() {
    // First, so no account write is issued after the deletes below ([writeDataItem] checks it).
    sessionState.value = null
    if (!::managerScope.isInitialized) return
    managerScope.launch {
      streamJob?.cancelAndJoin()
      deleteDataItems(WATCH_ACCOUNT_DATA)
    }
  }

  /** Deletes [targets] from the Data Layer, on every node that wrote them. */
  private suspend fun deleteDataItems(targets: List<WearDataItems>) {
    if (!wearableAvailableState.value) return
    for (target in targets) {
      val filter = if (target.prefix) DataClient.FILTER_PREFIX else DataClient.FILTER_LITERAL
      runCatching { dataClient.deleteDataItems(wearUri(target.path), filter).await() }
        .onFailure {
          if (it is CancellationException) throw it
          handleFailure("deleteDataItems ${target.path}", it)
        }
    }
  }

  /** The paths of the Data Layer items under [prefix]; empty if they can't be read. */
  private suspend fun dataItemPaths(prefix: String): List<String> {
    if (!wearableAvailableState.value) return emptyList()
    val buffer =
      runCatching { dataClient.getDataItems(wearUri(prefix), DataClient.FILTER_PREFIX).await() }
        .onFailure {
          if (it is CancellationException) throw it
          handleFailure("getDataItems $prefix", it)
        }
        .getOrNull() ?: return emptyList()
    return try {
      buffer.mapNotNull { it.uri.path }
    } finally {
      buffer.release()
    }
  }

  /** Account items (pins, slots) are held back while signed out and re-sent on the next session. */
  private fun <T : Any> Flow<T>.whileSignedIn(): Flow<T> =
    this.combine(sessionState.map { it != null }.distinctUntilChanged()) { value, signedIn ->
        value.takeIf { signedIn }
      }
      .distinctUntilChanged()
      .filterNotNull()

  /**
   * Whether the wear node currently has an active lease. Combined with pinned-cards count to decide
   * between streaming and batched DataStore writes.
   */
  override val streamActive: StateFlow<Boolean> by lazy {
    leaseState.map { isLeaseFresh(it) }.stateIn(managerScope, SharingStarted.Eagerly, false)
  }

  override val wearableAvailable: StateFlow<Boolean> = wearableAvailableState

  /**
   * Wires the manager to its data sources. Call once from Application.onCreate. Subsequent session
   * changes use [setSession].
   */
  override fun start(
    scope: CoroutineScope,
    prefs: PreferencesStore,
    pinStore: PinStore,
    slotsStore: WearWidgetSlotsStore,
    inputEditing: StateFlow<Boolean>,
  ) {
    managerScope = scope
    inputEditingMode = inputEditing
    // A phone where any Wearable call would crash the app: stay unavailable for good, which every
    // Data Layer path already checks, and start nothing (see WearableApiGuard).
    if (!WearableApiGuard.isUsable(context)) {
      wearableAvailableState.value = false
      return
    }
    // addListener returns a Task; observe it so an API_NOT_CONNECTED
    // failure (no Wearable component on this device) flips us into
    // the disabled state instead of silently never delivering leases.
    runCatching {
      messageClient.addListener(leaseListener).addOnFailureListener {
        handleFailure("addListener", it)
      }
    }
      .onFailure { handleFailure("addListener", it) }

    // Demo-mode flag → /wear/settings (and base url annotation).
    scope.launch {
      combine(prefs.demoMode, sessionState, inputEditing) { demo, session, editing ->
          WearSettings(
            demoMode = demo,
            inputEditingEnabled =
              editing &&
                !demo &&
                session != null &&
                session !is ee.schimke.terrazzo.core.session.DemoHaSession,
            baseUrl = session?.baseUrl.orEmpty(),
            updatedAtMs = System.currentTimeMillis(),
          )
        }
        .distinctUntilChanged()
        .collect { writeDataItem(WearSyncPaths.SETTINGS, encodeProto(it)) }
    }

    // Pinned cards → /wear/pinned. Source switched from
    // WidgetStore (phone home-screen widgets) to the new PinStore so
    // the watch's top-level nav reflects the user's explicit Wear
    // pins, decoupled from the phone widget set.
    // Each card resolved against the registry first: the watch has none to resolve a Bambu card's
    // configured printer with.
    scope.launch {
      combine(pinStore.cards, CardDesignOverrideStore.of(context).overrides, registrySnapshot) {
          cards,
          overrides,
          snapshot ->
          val pinned = cards.toWearPinnedCards(overrides, updatedAtMs = 0, snapshot = snapshot)
          pinned to wearEntityIds(pinned.cards.map { it.card.rawJson }, snapshot)
        }
        .distinctUntilChanged()
        .whileSignedIn()
        .collect { (cards, entities) ->
          // The values follow the cards the watch has: only once these are written.
          if (
            writeDataItem(
              WearSyncPaths.PINNED,
              encodeProto(cards.copy(updatedAtMs = System.currentTimeMillis())),
            )
          ) {
            pinnedEntities.value = entities
          }
        }
    }

    // Pinned sections → /wear/sections.
    scope.launch {
      combine(pinStore.sections, registrySnapshot, CardDesignOverrideStore.of(context).overrides) {
          sections,
          snapshot,
          overrides ->
          val pinned =
            sections.toWearPinnedSections(overrides, updatedAtMs = 0, snapshot = snapshot)
          pinned to
            wearEntityIds(
              pinned.sections.flatMap { section -> section.cards.map { it.rawJson } },
              snapshot,
            )
        }
        .distinctUntilChanged()
        .whileSignedIn()
        .collect { (sections, entities) ->
          if (
            writeDataItem(
              WearSyncPaths.SECTIONS,
              encodeProto(sections.copy(updatedAtMs = System.currentTimeMillis())),
            )
          ) {
            sectionEntities.value = entities
          }
        }
    }

    // Wear-widget slots → /wear/slots. Empty assignments survive
    // the wire so the watch can disable matching widget providers.
    scope.launch {
      slotsStore.slots
        .map { it.toProto() }
        .distinctUntilChanged()
        .whileSignedIn()
        .collect { writeDataItem(WearSyncPaths.SLOTS, encodeProto(it)) }
    }

    // Wear widget slot cards → whose pictures [pushPictures] and Wear designs [pushDesigns] send.
    scope.launch {
      combine(slotsStore.slots, pinStore.cards) { slots, cards ->
          val byKey = cards.associateBy { it.key }
          slots
            .filter { it.isAssigned }
            .mapNotNull { slot ->
              byKey[slot.cardKey]?.card?.rawJson?.toCardConfigOrNull()?.let { slot.cardKey to it }
            }
        }
        .distinctUntilChanged()
        .collect { wearSlotCards.value = it }
    }

    // Slot cards' Wear widget designs → /wear/slot-designs and /wear/design/<sha256>.
    scope.launch {
      combine(wearSlotCards, CardDesignPreload.designs.filterNotNull(), registrySnapshot) {
          cards,
          designs,
          snapshot ->
          val picked = wearSlotDesigns(cards, snapshot, designs)
          picked.map { it.first } to picked.associate { it.first.sha256 to it.second }
        }
        .whileSignedIn()
        .collect { (slotDesigns, blobs) -> pushDesigns(slotDesigns, blobs) }
    }

    // Republish dashboard styles even when neither pins nor entity values change.
    scope.launch {
      combine(
          sessionState,
          CardDesignOverrideStore.of(context).overrides,
          wearableAvailableState,
        ) { session, overrides, available ->
          Triple(session, overrides, available)
        }
        .collectLatest { (session, overrides, available) ->
          if (session != null && available) publishDashboards(session, overrides)
        }
    }

    // A watch that asked for its own token while no session could issue one is answered once the
    // session connects.
    scope.launch {
      sessionState.collectLatest { session ->
        if (session == null) return@collectLatest
        session.connectionStatus.collect { status ->
          if (status != SessionConnectionStatus.Connected) return@collect
          runCatching { accessIssuer.onConnected(session) }
            .onFailure {
              if (it is CancellationException) throw it
              handleFailure("accessIssue", it)
            }
        }
      }
    }

    // Session snapshot pump. Re-driven on every session change so the
    // demo session and live session each get their own loop. The
    // streaming-vs-batched decision now keys off the unified pin set
    // (cards + sections); any pin keeps the live stream warm.
    scope.launch {
      combine(sessionState, pinStore.cards, pinStore.sections) { s, cards, sections ->
          Triple(s, cards.isNotEmpty() || sections.isNotEmpty(), Unit)
        }
        .collectLatest { (session, hasPinned, _) ->
          streamJob?.cancel()
          streamJob = null
          if (session == null) return@collectLatest
          streamJob = scope.launch { sessionPump(session, hasPinned) }
        }
    }
  }

  /** Stops listening; safe to call from Application.onTerminate (rare). */
  fun stop() {
    if (WearableApiGuard.isUsable(context)) {
      runCatching { messageClient.removeListener(leaseListener) }
    }
    streamJob?.cancel()
    recoveryJob?.cancel()
  }

  /**
   * Wear → phone path. Ephemeral message delivered if wear-side activity is foreground. We record
   * the lease arrival as the manager's window source.
   */
  @Suppress("unused")
  private fun isLeaseFresh(at: Long): Boolean {
    if (at <= 0L) return false
    val now = System.currentTimeMillis()
    return now - at <= WearSyncPaths.LEASE_WINDOW_MS
  }

  private suspend fun publishDashboards(session: HaSession, overrides: CardDesignOverrides) {
    if (!wearableAvailableState.value) return
    // Wait until the session has finished its initial connect; otherwise
    // listDashboards() hits "Not connected" because the UI drives
    // session.connect() lazily via the dashboard list.
    session.connectionStatus.first { it == SessionConnectionStatus.Connected }
    runCatching {
      val summaries = session.listDashboards()
      for (summary in summaries) {
        // One dashboard the app can't render (a strategy dashboard, one Home Assistant refuses)
        // mustn't keep the others from the watch.
        val (dashboard, snapshot) =
          runCatching { session.loadDashboard(summary.urlPath) }
            .onFailure {
              if (it is CancellationException) throw it
              handleFailure("loadDashboard ${summary.urlPath ?: "(default)"}", it)
            }
            .getOrNull() ?: continue
        val cards =
          dashboard.views.flatMap { it.cards + it.sections.flatMap { section -> section.cards } }
        inputCards.value =
          inputCards.value +
            cards
              .filter { it.type == ee.schimke.ha.model.MESSAGE_CARD_TYPE }
              .associateBy { json.encodeToString(JsonObject.serializer(), it.raw) }
        val data =
          DashboardData(
            urlPath = summary.urlPath ?: "",
            title = dashboard.title ?: summary.title,
            cards = cards.map { it.toWearDashboardSummary(snapshot, overrides) },
            updatedAtMs = System.currentTimeMillis(),
          )
        val path = WearSyncPaths.dashboardPath(summary.urlPath)
        if (writeDataItem(path, encodeProto(data))) {
          dashboardEntities.value =
            dashboardEntities.value +
              (path to wearEntityIds(data.cards.map { it.rawJson }, snapshot))
        }
      }
      // Every dashboard listed and written: drop the ones Home Assistant no longer has.
      val current = summaries.map { WearSyncPaths.dashboardPath(it.urlPath) }
      dashboardEntities.value = dashboardEntities.value.filterKeys { it in current }
      val stale = staleDashboardPaths(dataItemPaths(WearSyncPaths.DASHBOARD_PREFIX), current)
      deleteDataItems(stale.map { WearDataItems(it, prefix = false) })
    }
      .onFailure {
        if (it is CancellationException) throw it
        handleFailure("publishDashboards", it)
      }
  }

  /**
   * Repeatedly fetches snapshots from [session]; streams via MessageClient (deltas) when wear has a
   * lease or pinned cards exist, and writes the DataStore-Proto entry whenever the watch app isn't
   * open (its widgets read that one). Live sessions that don't expose a refresh interval fall back
   * to a 30 s heartbeat so values aren't infinitely stale.
   */
  private suspend fun sessionPump(session: HaSession, hasPinned: Boolean) {
    val cadence = session.refreshIntervalMillis ?: 30_000L
    // Reset snapshot baseline so the first push is a full set, and resend this session's pictures
    // and Wear designs (a sign-out deleted them from the watch).
    lastSnapshot = emptyMap()
    lastValuesItem = null
    pushedPictures.clear()
    pushedDesigns.clear()
    lastSlotDesigns = null
    session.connectionStatus.first { it == SessionConnectionStatus.Connected }
    while (true) {
      // Suspend (rather than exit) when the wear API isn't usable
      // so a transient API_NOT_CONNECTED disconnect pauses the
      // pump and resumes once the recovery probe re-flips the flag.
      wearableAvailableState.first { it }
      // The states alone: never the default dashboard, whose config may be auto-generated or a
      // strategy the app can't load, which would keep every value from the watch.
      val snapshot = runCatching {
        session.loadSnapshot()
      }
        .onFailure {
          if (it is CancellationException) throw it
          handleFailure("loadSnapshot", it)
        }
        .getOrNull()
      if (snapshot != null) {
        registrySnapshot.value = snapshot
        val current = wearValues(snapshot, watchEntityIds)
        // When the snapshot was fetched: a cached one (app backgrounded, offline) isn't fresh.
        val capturedAtMs =
          valuesCapturedAtMs(session.snapshotFetchedAtMs(null), System.currentTimeMillis())
        val delivery = valuesDelivery(hasPinned, watchAppOpen = isLeaseFresh(leaseState.value))
        if (delivery.stream) pushStream(current, capturedAtMs)
        if (delivery.dataItem) pushValuesItem(current, capturedAtMs)
        pushPictures(session.baseUrl, snapshot)
      }
      delay(cadence)
    }
  }

  /** The `/wear/values` DataItem, when [values] changed since the last one written. */
  private suspend fun pushValuesItem(values: Map<String, EntityValue>, capturedAtMs: Long) {
    if (values == lastValuesItem) return
    val payload = LiveValues(values = values, capturedAtMs = capturedAtMs)
    // Urgent: it is the widgets' only update path while the app is closed, and a non-urgent item
    // can wait up to 30 minutes to sync. Recorded only once written, so a failed write is retried.
    if (writeDataItem(WearSyncPaths.VALUES, encodeProto(payload))) lastValuesItem = values
  }

  private suspend fun pushStream(values: Map<String, EntityValue>, capturedAtMs: Long) {
    if (!wearableAvailableState.value) return
    val deltas =
      streamDeltas(lastSnapshot, values).map { (k, v) -> EntityDelta(entityId = k, value = v) }
    if (deltas.isEmpty()) return
    val frame = StreamUpdate(deltas = deltas, capturedAtMs = capturedAtMs)
    val delivered = runCatching {
      val nodes = nodeClient.connectedNodes.await()
      val bytes = encodeProto(frame)
      for (node in nodes) {
        messageClient.sendMessage(node.id, WearSyncPaths.STREAM_MESSAGE, bytes).await()
      }
      statsStore.recordMessageSent(System.currentTimeMillis())
      nodes.isNotEmpty()
    }
      .onFailure {
        if (it is CancellationException) throw it
        handleFailure("pushStream", it)
      }
      .getOrDefault(false)
    // A failed (or watchless) send keeps the old baseline, so its deltas go out again next tick.
    lastSnapshot = streamBaselineAfterSend(lastSnapshot, values, delivered)
  }

  /**
   * Wear widgets can't fetch pictures, so the phone sends one per pictured entity in a widget slot
   * card ([WearPicture], `/wear/picture/<entityId>`): when its URL changes, at most every
   * [PICTURE_REFRESH_MS], and only when the PNG differs from the one last sent.
   */
  private suspend fun pushPictures(baseUrl: String, snapshot: HaSnapshot) {
    if (!wearableAvailableState.value) return
    val now = System.currentTimeMillis()
    for ((entityId, url) in wearPictureUrls(wearSlotCards.value.map { it.second }, snapshot)) {
      val last = pushedPictures[entityId]
      if (last != null && now - last.atMs < PICTURE_REFRESH_MS) continue
      val png =
        runCatching { fetchPicture(baseUrl, url) }
          .onFailure { if (it is CancellationException) throw it }
          .getOrNull() ?: continue
      val hash = png.contentHashCode()
      val changed = last == null || last.url != url || last.pngHash != hash
      val picture = WearPicture(entityId = entityId, url = url, png = png, updatedAtMs = now)
      // Recorded only once written, so a failed write is retried on the next tick.
      if (!changed || writeDataItem(WearSyncPaths.picturePath(entityId), encodeProto(picture))) {
        pushedPictures[entityId] = PushedPicture(url, hash, now)
      }
    }
  }

  /**
   * The slot cards' Wear widget designs: each blob once ([WearDesignBlob], content-addressed), then
   * the index naming them ([WearSlotDesigns]), then the blobs no slot uses any more are deleted.
   * The index is written only once every blob it names is, so the watch never looks one up before
   * it can arrive. Recorded only once all of it is written, so a failed write is retried on the
   * next snapshot.
   */
  private suspend fun pushDesigns(designs: List<WearSlotDesign>, blobs: Map<String, ByteArray>) {
    if (!wearableAvailableState.value || designs == lastSlotDesigns) return
    for ((sha, rc) in blobs) {
      if (sha in pushedDesigns) continue
      if (!writeDataItem(WearSyncPaths.designPath(sha), encodeProto(WearDesignBlob(sha, rc)))) {
        return
      }
      pushedDesigns += sha
    }
    val index = WearSlotDesigns(designs = designs, updatedAtMs = System.currentTimeMillis())
    if (!writeDataItem(WearSyncPaths.SLOT_DESIGNS, encodeProto(index))) return
    val current = blobs.keys.map(WearSyncPaths::designPath).toSet()
    val stale = dataItemPaths(WearSyncPaths.DESIGN_PREFIX).filter { it !in current }
    deleteDataItems(stale.map { WearDataItems(it, prefix = false) })
    pushedDesigns.retainAll(blobs.keys)
    lastSlotDesigns = designs
  }

  /** Writes [bytes] at [path]; false when the wearable API is unavailable or the write failed. */
  private suspend fun writeDataItem(path: String, bytes: ByteArray): Boolean {
    if (!wearableAvailableState.value) return false
    // Signed out: nothing of the account may land after [clearWatchData]'s deletes.
    if (path != WearSyncPaths.SETTINGS && sessionState.value == null) return false
    return runCatching {
      val request =
        PutDataMapRequest.create(path).apply {
          dataMap.putByteArray(KEY_PROTO, bytes)
          dataMap.putLong(KEY_TS, System.currentTimeMillis())
        }
      dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
      statsStore.recordWrite(System.currentTimeMillis())
    }
      .onFailure {
        if (it is CancellationException) throw it
        handleFailure("writeDataItem $path", it)
      }
      .isSuccess
  }

  /**
   * Funnels every wear data-layer failure through one place so the "API unavailable" case (no
   * wearable component / no paired watch) pauses the manager after a single log line instead of
   * warning on every snapshot tick. A recovery probe flips availability back once Google Play
   * Services is reachable again, so a transient disconnect (or a pair-after-launch) doesn't stay
   * disabled for the rest of the process. Other failures still surface at WARN.
   */
  private fun handleFailure(operation: String, error: Throwable) {
    if (isWearableUnavailable(error)) {
      if (wearableAvailableState.compareAndSet(expect = true, update = false)) {
        Log.i(TAG, "Wearable API unavailable; pausing wear sync ($operation)")
        scheduleRecoveryProbe()
      }
      return
    }
    Log.w(TAG, "$operation failed", error)
  }

  private fun isWearableUnavailable(error: Throwable): Boolean {
    val api = error as? ApiException ?: return false
    return api.statusCode == CommonStatusCodes.API_NOT_CONNECTED
  }

  /**
   * Polls [NodeClient.getConnectedNodes] while wear is marked unavailable; flips availability back
   * on the first successful call so transient API_NOT_CONNECTED disconnects (or pairing happening
   * after app start) don't latch the manager off forever.
   */
  private fun scheduleRecoveryProbe() {
    if (!::managerScope.isInitialized) return
    recoveryJob?.cancel()
    recoveryJob = managerScope.launch {
      while (!wearableAvailableState.value) {
        delay(RECOVERY_PROBE_INTERVAL_MS)
        val recovered = runCatching { nodeClient.connectedNodes.await() }.isSuccess
        if (recovered) {
          Log.i(TAG, "Wearable API available again; resuming wear sync")
          wearableAvailableState.value = true
        }
      }
    }
  }

  companion object {
    private const val TAG = "WearSync"
    const val KEY_PROTO: String = "proto"
    const val KEY_TS: String = "ts"
    private const val RECOVERY_PROBE_INTERVAL_MS = 60_000L

    /** Wear widget pictures: the long edge, and one DataItem's size limit with headroom. */
    const val WEAR_PICTURE_MAX_PX = 384
    const val WEAR_PICTURE_MAX_BYTES = 90_000
    private const val PICTURE_REFRESH_MS = 5 * 60_000L
  }
}

/**
 * This state for the watch, which formats it with the same `formatState`: the display precision
 * travels with it, since the watch has neither the registry nor the other attributes.
 */
internal fun EntityState.toEntityValue(snapshot: HaSnapshot): EntityValue {
  val attrs = attributes
  return EntityValue(
    state = state,
    friendlyName = attrs["friendly_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    unit = attrs["unit_of_measurement"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    deviceClass = attrs["device_class"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    entityPicture = attrs["entity_picture"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    displayPrecision = displayPrecisionOf(this, snapshot),
    attributes =
      WEAR_EXTRA_ATTRIBUTES.mapNotNull { key ->
          // A safe cast: another integration may put an object or array under the same key, which
          // is skipped rather than thrown on (that would stop the whole sync).
          (attrs[key] as? JsonPrimitive)?.contentOrNull?.let { key to it }
        }
        .toMap() +
        (attrs["options"] as? kotlinx.serialization.json.JsonArray)
          ?.let { mapOf("input_options" to it.toString()) }
          .orEmpty(),
  )
}

/**
 * The pictures Wear widgets need: `entity_picture` by entity, for every entity the slot [cards]
 * bind (stacks and grids included) that has one in [snapshot].
 */
internal fun wearPictureUrls(cards: List<CardConfig>, snapshot: HaSnapshot): Map<String, String> =
  cards
    .flatMap { CardDesignSlots.slotEntities(it).orEmpty() }
    .distinct()
    .mapNotNull { entityId ->
      val url =
        snapshot.states[entityId]?.attributes?.get("entity_picture")?.jsonPrimitive?.contentOrNull
      url?.let { entityId to it }
    }
    .toMap()

/**
 * The entities [rawJson] cards read: every entity-id-shaped token in them (`light.kitchen`),
 * wherever it sits, so a markdown card's `states('sensor.x')` and nested cards count as well as
 * `entity` fields, plus every entity of [snapshot]'s Bambu printers for a card holding a Bambu Lab
 * card, which names its sensors from a printer prefix ([bambuEntityIds]). A token that names no
 * entity matches nothing in [wearValues].
 */
internal fun wearEntityIds(rawJson: List<String>, snapshot: HaSnapshot): Set<String> =
  rawJson.flatMapTo(HashSet()) { raw ->
    val bambu = raw.toCardConfigOrNull()?.let { bambuEntityIds(it, snapshot) }.orEmpty()
    ENTITY_ID.findAll(raw).map { it.value }.toList() + bambu
  }

private val ENTITY_ID = Regex("""\b[a-z_][a-z0-9_]*\.[a-z0-9_]+\b""")

/**
 * The values to send the watch: those of [entityIds] that [snapshot] has. Never all of Home
 * Assistant's: a large home's states outgrow one DataItem and one message (about 100 KB each), and
 * then nothing reaches the watch and every card reads unavailable.
 */
internal fun wearValues(snapshot: HaSnapshot, entityIds: Set<String>): Map<String, EntityValue> =
  entityIds
    .mapNotNull { id -> snapshot.states[id]?.let { id to it.toEntityValue(snapshot) } }
    .toMap()

/** How entity values reach the watch on one pump tick. */
internal data class ValuesDelivery(val stream: Boolean, val dataItem: Boolean)

/**
 * Values stream (MessageClient deltas) while the watch app is open, or whenever cards are pinned to
 * keep it warm. The stream only reaches a running app, though: its widgets render with the app
 * closed and read the persistent `/wear/values` DataItem, so that one is written whenever the app
 * isn't open.
 */
internal fun valuesDelivery(hasPinned: Boolean, watchAppOpen: Boolean): ValuesDelivery =
  ValuesDelivery(stream = hasPinned || watchAppOpen, dataItem = !watchAppOpen)

private class PushedPicture(val url: String, val pngHash: Int, val atMs: Long)

/**
 * This pinned card as the watch should get it: a Bambu Lab card's configured printer resolved
 * through [snapshot]'s device registry ([resolveBambuDevices]), which the watch doesn't have.
 * Anything else, or a snapshot without the registry, is unchanged.
 */
internal fun PinnedCardData.resolvedFor(snapshot: HaSnapshot): PinnedCardData {
  // Cheap filter first: this runs for every pin on every snapshot tick.
  if (snapshot.devices.isEmpty() || BAMBU_CARD_MARKER !in rawJson) return this
  val card = rawJson.toCardConfigOrNull() ?: return this
  val resolved = resolveBambuDevices(card, snapshot)
  if (resolved == card) return this
  return copy(rawJson = json.encodeToString(JsonObject.serializer(), resolved.raw))
}

@JvmName("pinnedCardsResolvedFor")
internal fun List<MobilePinnedCard>.resolvedFor(snapshot: HaSnapshot): List<MobilePinnedCard> =
  map {
    it.copy(card = it.card.resolvedFor(snapshot))
  }

@JvmName("pinnedSectionsResolvedFor")
internal fun List<MobilePinnedSection>.resolvedFor(
  snapshot: HaSnapshot
): List<MobilePinnedSection> = map { section ->
  section.copy(cards = section.cards.map { it.resolvedFor(snapshot) })
}

/** In every Bambu Lab card type (`custom:ha-bambulab-…`), stacked or not. */
private const val BAMBU_CARD_MARKER = "ha-bambulab"

private fun String.toCardConfigOrNull(): CardConfig? = runCatching {
  val raw = json.parseToJsonElement(this) as JsonObject
  val type = raw["type"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
  CardConfig(type = type, raw = raw)
}
  .getOrNull()

private fun CardConfig.toSummary(states: Map<String, EntityState> = emptyMap()): CardSummary {
  val raw = this.raw
  val title =
    raw["title"]?.jsonPrimitive?.contentOrNull
      ?: raw["heading"]?.jsonPrimitive?.contentOrNull
      ?: raw["name"]?.jsonPrimitive?.contentOrNull
      ?: this.type
  val primary =
    raw["entity"]?.jsonPrimitive?.contentOrNull
      ?: raw["entities"]?.jsonArray?.firstOrNull()?.let {
        (it as? JsonPrimitive)?.contentOrNull
          ?: (it as? JsonObject)?.get("entity")?.jsonPrimitive?.contentOrNull
      }
      ?: ""
  val resolvedTitle =
    states[primary]?.attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull ?: title
  return CardSummary(
    type = this.type,
    title = resolvedTitle,
    primaryEntityId = primary,
    rawJson = json.encodeToString(JsonObject.serializer(), raw),
  )
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * Convert the mobile pin store's pinned-card list into the proto wire shape the watch consumes.
 * Captured fields flow through unchanged — the pin store is the source of truth for title / primary
 * entity at the time of pinning.
 */
internal fun List<MobilePinnedCard>.toWearPinnedCards(
  overrides: CardDesignOverrides,
  updatedAtMs: Long = System.currentTimeMillis(),
  snapshot: HaSnapshot = HaSnapshot(),
): PinnedCardSet =
  PinnedCardSet(
    cards =
      this.sortedBy { it.orderIndex }
        .map { entry ->
          PinnedCard(
            baseUrl = entry.baseUrl,
            card =
              entry.card
                .resolvedFor(snapshot)
                .toProto()
                .withStyleOverride(overrides, entry.card.rawJson),
            cardKey = entry.key,
            orderIndex = entry.orderIndex,
          )
        },
    updatedAtMs = updatedAtMs,
  )

@JvmName("slotsToProto")
private fun List<WearWidgetSlot>.toProto(): WearWidgetSlots =
  WearWidgetSlots(
    slots =
      this.map {
        WidgetSlot(slotIndex = it.slotIndex, cardKey = it.cardKey, size = it.size.wireValue)
      },
    updatedAtMs = System.currentTimeMillis(),
  )

internal fun List<MobilePinnedSection>.toWearPinnedSections(
  overrides: CardDesignOverrides,
  updatedAtMs: Long = System.currentTimeMillis(),
  snapshot: HaSnapshot = HaSnapshot(),
): PinnedSectionSet =
  PinnedSectionSet(
    sections =
      this.sortedBy { it.orderIndex }
        .map { entry ->
          PinnedSection(
            baseUrl = entry.baseUrl,
            dashboardUrlPath = entry.dashboardUrlPath,
            viewPath = entry.viewPath,
            sectionIndex = entry.sectionIndex,
            title = entry.title,
            cards =
              entry.cards.map {
                it.resolvedFor(snapshot).toProto().withStyleOverride(overrides, it.rawJson)
              },
            sectionKey = entry.key,
            orderIndex = entry.orderIndex,
          )
        },
    updatedAtMs = updatedAtMs,
  )

/** Apply the same selection to pins, sections and dashboard cards, including clearing it. */
internal fun CardSummary.withStyleOverride(
  overrides: CardDesignOverrides,
  originalRawJson: String = rawJson,
): CardSummary =
  copy(
    cardStyleOverride =
      originalRawJson.toCardConfigOrNull()?.let { overrides.forCard(it)?.cardStyle }.orEmpty()
  )

/**
 * Resolve selection against the source config, before device resolution rewrites its fingerprint.
 */
internal fun CardConfig.toWearDashboardSummary(
  snapshot: HaSnapshot,
  overrides: CardDesignOverrides,
): CardSummary =
  resolveBambuDevices(this, snapshot)
    .toSummary(snapshot.states)
    .withStyleOverride(overrides, raw.toString())

private fun PinnedCardData.toProto(): CardSummary =
  CardSummary(
    type = this.type,
    title = this.title,
    primaryEntityId = this.primaryEntity,
    rawJson = this.rawJson,
  )

/** The Data Layer items at [path] written by any node: the URI with the `*` (any node) host. */
private fun wearUri(path: String): android.net.Uri =
  android.net.Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).authority("*").path(path).build()

internal suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
  kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value -> cont.resumeWith(Result.success(value)) }
    addOnFailureListener { error -> cont.resumeWith(Result.failure(error)) }
  }
