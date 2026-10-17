package ee.schimke.terrazzo.wearsync

import android.content.Context
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.horologist.data.WearDataLayerRegistry
import ee.schimke.ha.client.design.CardDesignOverrides
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
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
import ee.schimke.terrazzo.wearsync.proto.WearLease
import ee.schimke.terrazzo.wearsync.proto.WearPicture
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import ee.schimke.terrazzo.wearsync.proto.WearWidgetSlots
import ee.schimke.terrazzo.wearsync.proto.WidgetSlot
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import ee.schimke.terrazzo.widget.WidgetImages
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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

  /** The cards in assigned Wear widget slots: whose pictures the watch needs. */
  private val wearSlotCards: MutableStateFlow<List<CardConfig>> = MutableStateFlow(emptyList())

  /**
   * The session's latest snapshot, device registry included. The watch's values carry entity states
   * only, so the cards sent to it are resolved against this first ([resolvedFor]).
   */
  private val registrySnapshot: MutableStateFlow<HaSnapshot> = MutableStateFlow(HaSnapshot())

  /** Per entity: the picture URL last pushed, its PNG's hash, and when. */
  private val pushedPictures = HashMap<String, PushedPicture>()

  private val leaseListener = MessageClient.OnMessageReceivedListener { event ->
    if (event.path != WearSyncPaths.LEASE_MESSAGE) return@OnMessageReceivedListener
    val now = System.currentTimeMillis()
    val lease: WearLease = decodeProto<WearLease>(event.data) ?: WearLease(sentAtMs = now)
    leaseState.value = lease.sentAtMs.takeIf { it > 0 } ?: now
    managerScope.launch { statsStore.recordLease(now) }
  }

  /** Update the active session whenever login/demo state changes. */
  override fun setSession(session: HaSession?) {
    sessionState.value = session
  }

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
  ) {
    managerScope = scope
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
      combine(prefs.demoMode, sessionState) { demo, session ->
          WearSettings(
            demoMode = demo,
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
          cards.resolvedFor(snapshot) to overrides
        }
        .distinctUntilChanged()
        .collect { (cards, overrides) ->
          writeDataItem(WearSyncPaths.PINNED, encodeProto(cards.toWearPinnedCards(overrides)))
        }
    }

    // Pinned sections → /wear/sections.
    scope.launch {
      combine(pinStore.sections, registrySnapshot) { sections, snapshot ->
          sections.resolvedFor(snapshot)
        }
        .distinctUntilChanged()
        .collect { writeDataItem(WearSyncPaths.SECTIONS, encodeProto(it.toProto())) }
    }

    // Wear-widget slots → /wear/slots. Empty assignments survive
    // the wire so the watch can disable matching widget providers.
    scope.launch {
      slotsStore.slots
        .map { it.toProto() }
        .distinctUntilChanged()
        .collect { writeDataItem(WearSyncPaths.SLOTS, encodeProto(it)) }
    }

    // Wear widget slot cards → whose pictures [pushPictures] sends.
    scope.launch {
      combine(slotsStore.slots, pinStore.cards) { slots, cards ->
          val byKey = cards.associateBy { it.key }
          slots
            .filter { it.isAssigned }
            .mapNotNull { slot -> byKey[slot.cardKey]?.card?.rawJson?.toCardConfigOrNull() }
        }
        .distinctUntilChanged()
        .collect { wearSlotCards.value = it }
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
          publishDashboards(session)
          streamJob = scope.launch { sessionPump(session, hasPinned) }
        }
    }
  }

  /** Stops listening; safe to call from Application.onTerminate (rare). */
  fun stop() {
    runCatching { messageClient.removeListener(leaseListener) }
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

  private suspend fun publishDashboards(session: HaSession) {
    if (!wearableAvailableState.value) return
    // Wait until the session has finished its initial connect; otherwise
    // listDashboards() hits "Not connected" because the UI drives
    // session.connect() lazily via the dashboard list.
    session.connectionStatus.first { it == SessionConnectionStatus.Connected }
    runCatching {
      val summaries = session.listDashboards()
      for (summary in summaries) {
        val (dashboard, snapshot) = session.loadDashboard(summary.urlPath)
        val cards = dashboard.views.flatMap { it.cards }
        val data =
          DashboardData(
            urlPath = summary.urlPath ?: "",
            title = dashboard.title ?: summary.title,
            cards = cards.map { resolveBambuDevices(it, snapshot).toSummary(snapshot.states) },
            updatedAtMs = System.currentTimeMillis(),
          )
        writeDataItem(WearSyncPaths.dashboardPath(summary.urlPath), encodeProto(data))
      }
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
    // Reset snapshot baseline so the first push is a full set, and resend this session's pictures.
    lastSnapshot = emptyMap()
    lastValuesItem = null
    pushedPictures.clear()
    session.connectionStatus.first { it == SessionConnectionStatus.Connected }
    while (true) {
      // Suspend (rather than exit) when the wear API isn't usable
      // so a transient API_NOT_CONNECTED disconnect pauses the
      // pump and resumes once the recovery probe re-flips the flag.
      wearableAvailableState.first { it }
      val snapshot = runCatching {
        session.loadDashboard(null).second
      }
        .onFailure { if (it is CancellationException) throw it }
        .getOrNull()
      if (snapshot != null) {
        registrySnapshot.value = snapshot
        val current = snapshot.states.mapValues { (_, state) -> state.toEntityValue(snapshot) }
        val delivery = valuesDelivery(hasPinned, watchAppOpen = isLeaseFresh(leaseState.value))
        if (delivery.stream) pushStream(current)
        if (delivery.dataItem) pushValuesItem(current)
        pushPictures(session.baseUrl, snapshot)
      }
      delay(cadence)
    }
  }

  /** The `/wear/values` DataItem, when [values] changed since the last one written. */
  private suspend fun pushValuesItem(values: Map<String, EntityValue>) {
    if (values == lastValuesItem) return
    val payload = LiveValues(values = values, capturedAtMs = System.currentTimeMillis())
    // Urgent: it is the widgets' only update path while the app is closed, and a non-urgent item
    // can wait up to 30 minutes to sync. Recorded only once written, so a failed write is retried.
    if (writeDataItem(WearSyncPaths.VALUES, encodeProto(payload))) lastValuesItem = values
  }

  private suspend fun pushStream(values: Map<String, EntityValue>) {
    if (!wearableAvailableState.value) return
    val deltas =
      values.entries
        .filter { (k, v) -> lastSnapshot[k] != v }
        .map { (k, v) -> EntityDelta(entityId = k, value = v) }
    if (deltas.isEmpty()) return
    val frame = StreamUpdate(deltas = deltas, capturedAtMs = System.currentTimeMillis())
    runCatching {
      val nodes = nodeClient.connectedNodes.await()
      val bytes = encodeProto(frame)
      for (node in nodes) {
        messageClient.sendMessage(node.id, WearSyncPaths.STREAM_MESSAGE, bytes).await()
      }
      statsStore.recordMessageSent(System.currentTimeMillis())
    }
      .onFailure {
        if (it is CancellationException) throw it
        handleFailure("pushStream", it)
      }
    lastSnapshot = values
  }

  /**
   * Wear widgets can't fetch pictures, so the phone sends one per pictured entity in a widget slot
   * card ([WearPicture], `/wear/picture/<entityId>`): when its URL changes, at most every
   * [PICTURE_REFRESH_MS], and only when the PNG differs from the one last sent.
   */
  private suspend fun pushPictures(baseUrl: String, snapshot: HaSnapshot) {
    if (!wearableAvailableState.value) return
    val now = System.currentTimeMillis()
    for ((entityId, url) in wearPictureUrls(wearSlotCards.value, snapshot)) {
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

  /** Writes [bytes] at [path]; false when the wearable API is unavailable or the write failed. */
  private suspend fun writeDataItem(path: String, bytes: ByteArray): Boolean {
    if (!wearableAvailableState.value) return false
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
        .toMap(),
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
): PinnedCardSet =
  PinnedCardSet(
    cards =
      this.sortedBy { it.orderIndex }
        .map { entry ->
          PinnedCard(
            baseUrl = entry.baseUrl,
            card =
              entry.card
                .toProto()
                .copy(
                  cardStyleOverride =
                    entry.card.rawJson
                      .toCardConfigOrNull()
                      ?.let { overrides.forCard(it)?.cardStyle }
                      .orEmpty()
                ),
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

@JvmName("sectionsToProto")
private fun List<MobilePinnedSection>.toProto(): PinnedSectionSet =
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
            cards = entry.cards.map { it.toProto() },
            sectionKey = entry.key,
            orderIndex = entry.orderIndex,
          )
        },
    updatedAtMs = System.currentTimeMillis(),
  )

private fun PinnedCardData.toProto(): CardSummary =
  CardSummary(
    type = this.type,
    title = this.title,
    primaryEntityId = this.primaryEntity,
    rawJson = this.rawJson,
  )

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
  kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value -> cont.resumeWith(Result.success(value)) }
    addOnFailureListener { error -> cont.resumeWith(Result.failure(error)) }
  }
