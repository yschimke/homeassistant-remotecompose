package ee.schimke.terrazzo.wear.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.data.WearDataLayerRegistry
import ee.schimke.terrazzo.wear.widget.WearWidgetCardStore
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import ee.schimke.terrazzo.wearsync.proto.StreamUpdate
import ee.schimke.terrazzo.wearsync.proto.WearAccess
import ee.schimke.terrazzo.wearsync.proto.WearDesignBlob
import ee.schimke.terrazzo.wearsync.proto.WearLease
import ee.schimke.terrazzo.wearsync.proto.WearPicture
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import ee.schimke.terrazzo.wearsync.proto.WearSlotDesigns
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Watch-side counterpart to `MobileWearSyncManager`. Keeps state for:
 *
 * - [settings]: cross-device flags (demo mode, base url) — sourced from the `/wear/settings` Proto
 *   DataStore entry
 * - [pinned]: phone's pinned card set
 * - [sections]: phone's pinned section set (each becomes a Wear nav destination)
 * - [dashboards]: one entry per `/wear/dashboard/<urlPath>`
 * - [values]: latest entity values, merged from the `/wear/values` DataStore entry (cold reads) and
 *   `/wear/stream` MessageClient deltas (hot path)
 *
 * Streaming deltas are merged into the local [values] state so consumers see one consistent view
 * regardless of cadence.
 */
@OptIn(ExperimentalHorologistApi::class)
class WearSyncRepository(context: Context, private val scope: CoroutineScope) {
  private val dataClient: DataClient = Wearable.getDataClient(context)
  private val messageClient: MessageClient = Wearable.getMessageClient(context)
  private val nodeClient: NodeClient = Wearable.getNodeClient(context)

  @Suppress("unused")
  private val registry: WearDataLayerRegistry =
    WearDataLayerRegistry.fromContext(
      application = context.applicationContext as android.app.Application,
      coroutineScope = scope,
    )

  /**
   * Local on-disk mirror of every proto blob the phone has ever pushed. Read once at [start] so the
   * watch boots from disk when the phone is unreachable; written on every [handle] so the next cold
   * launch sees the latest values without waiting for a sync.
   */
  private val offline: WearOfflineStore = WearOfflineStore(context.applicationContext)

  /** Reads the cards' values from Home Assistant itself when the phone has sent none lately. */
  private val direct = WearDirectAccess(context.applicationContext, offline = offline)

  /** The card each placed widget shows: forgotten with the account on sign-out. */
  private val widgetCards = WearWidgetCardStore(context.applicationContext)

  private val _settings: MutableStateFlow<WearSettings> =
    MutableStateFlow(offline.readSettings() ?: WearSettings())
  val settings: StateFlow<WearSettings> = _settings.asStateFlow()

  private val _pinned: MutableStateFlow<PinnedCardSet> =
    MutableStateFlow(offline.readPinned() ?: PinnedCardSet())
  val pinned: StateFlow<PinnedCardSet> = _pinned.asStateFlow()

  private val _sections: MutableStateFlow<PinnedSectionSet> =
    MutableStateFlow(offline.readSections() ?: PinnedSectionSet())
  val sections: StateFlow<PinnedSectionSet> = _sections.asStateFlow()

  private val _dashboards: MutableStateFlow<List<DashboardData>> =
    MutableStateFlow(offline.readAllDashboards().sortedBy { it.title })
  val dashboards: StateFlow<List<DashboardData>> = _dashboards.asStateFlow()

  private val _slotDesigns: MutableStateFlow<WearSlotDesigns> =
    MutableStateFlow(offline.readSlotDesigns() ?: WearSlotDesigns())
  /** The pinned cards' Wear widget designs the phone chose: placed widgets redraw on a change. */
  val slotDesigns: StateFlow<WearSlotDesigns> = _slotDesigns.asStateFlow()

  private val _values: MutableStateFlow<Map<String, EntityValue>> =
    MutableStateFlow(offline.readValues()?.values ?: emptyMap())
  val values: StateFlow<Map<String, EntityValue>> = _values.asStateFlow()

  private val dataListener = DataClient.OnDataChangedListener { events: DataEventBuffer ->
    for (event in events) {
      if (event.type == DataEvent.TYPE_DELETED) forget(event.dataItem.uri.path.orEmpty())
      if (event.type != DataEvent.TYPE_CHANGED) continue
      val item = event.dataItem
      val mapItem = DataMapItem.fromDataItem(item)
      val bytes = mapItem.dataMap.getByteArray(KEY_PROTO) ?: continue
      handle(item.uri.path.orEmpty(), bytes)
    }
    events.release()
  }

  private val inputRequests =
    java.util.concurrent.ConcurrentHashMap<
      String,
      kotlinx.coroutines.CompletableDeferred<ByteArray>,
    >()

  /** Foreground app only. No offline queue or automatic retry of a side-effecting send. */
  suspend fun sendInputs(card: String, values: Map<String, String>) {
    check(
      _settings.value.inputEditingEnabled &&
        !_settings.value.demoMode &&
        _settings.value.baseUrl.isNotEmpty()
    ) {
      "Sign in on the phone"
    }
    val node = nodeClient.connectedNodes.await().firstOrNull() ?: error("Connect the phone to send")
    val id = java.util.UUID.randomUUID().toString()
    val response = kotlinx.coroutines.CompletableDeferred<ByteArray>()
    inputRequests[id] = response
    try {
      val request =
        kotlinx.serialization.json
          .buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(id))
            put("card", kotlinx.serialization.json.JsonPrimitive(card))
            put(
              "values",
              kotlinx.serialization.json.JsonObject(
                values.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }
              ),
            )
          }
          .toString()
          .encodeToByteArray()
      require(request.size <= 32768) { "Message is too long" }
      messageClient.sendMessage(node.id, "/wear/input/v1", request).await()
      val result =
        kotlinx.serialization.json.Json.parseToJsonElement(
            kotlinx.coroutines.withTimeout(15000) { response.await() }.decodeToString()
          )
          .jsonObject
      check(result["ok"]?.jsonPrimitive?.content == "true") {
        result["error"]?.jsonPrimitive?.content ?: "Message was not sent"
      }
    } finally {
      inputRequests.remove(id)
    }
  }

  private val messageListener = MessageClient.OnMessageReceivedListener { event ->
    if (event.path == "/wear/input-result/v1") {
      val result = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(event.data.decodeToString()).jsonObject
      }
        .getOrNull()
      result?.get("id")?.jsonPrimitive?.content?.let { inputRequests[it]?.complete(event.data) }
      return@OnMessageReceivedListener
    }
    if (event.path == WearSyncPaths.ACCESS_MESSAGE) {
      decodeProto<WearAccess>(event.data)?.let { direct.accept(it, _settings.value) }
      return@OnMessageReceivedListener
    }
    if (event.path != WearSyncPaths.STREAM_MESSAGE) return@OnMessageReceivedListener
    val frame: StreamUpdate = decodeProto(event.data) ?: return@OnMessageReceivedListener
    val merged = mergeStreamUpdate(offline.readValues(), frame) ?: return@OnMessageReceivedListener
    _values.value = merged.values
    // Persist the merged snapshot so a process restart resumes from
    // the latest delta-updated state, not the last full DataItem.
    offline.writeValues(merged)
  }

  fun start() {
    dataClient.addListener(dataListener)
    messageClient.addListener(messageListener)
    // Cold-read all known paths so the UI lights up immediately on
    // launch instead of waiting for the next change event.
    scope.launch {
      runCatching { dataClient.dataItems.await() }
        .getOrNull()
        ?.let { buffer ->
          for (i in 0 until buffer.count) {
            val item = buffer[i]
            val mapItem = DataMapItem.fromDataItem(item)
            val bytes = mapItem.dataMap.getByteArray(KEY_PROTO) ?: continue
            handle(item.uri.path.orEmpty(), bytes)
          }
          buffer.release()
        }
      direct.requestIfMissing(_settings.value)
    }
  }

  /**
   * Reads the values of the watch's cards (pinned, in pinned sections and on its dashboards) from
   * Home Assistant when the phone's are stale: see [WearDirectAccess]. The app calls this while it
   * is in the foreground.
   */
  suspend fun refreshDirect() {
    val cards =
      _pinned.value.cards.map { it.card.rawJson } +
        _sections.value.sections.flatMap { section -> section.cards.map { it.rawJson } } +
        _dashboards.value.flatMap { board -> board.cards.map { it.rawJson } }
    runCatching { direct.refresh(cards.filter { it.isNotEmpty() }, _settings.value) }
      .onFailure {
        if (it is kotlinx.coroutines.CancellationException) throw it
        Log.w(TAG, "refreshDirect failed", it)
      }
      .getOrNull()
      ?.let { _values.value = it.values }
  }

  fun stop() {
    runCatching { dataClient.removeListener(dataListener) }
    runCatching { messageClient.removeListener(messageListener) }
  }

  /**
   * Push a heartbeat to the phone. Wear's lifecycle controller calls this every
   * [WearLeaseController.HEARTBEAT_MS] while the activity is foreground; phone treats arrival time
   * as the lease window.
   */
  suspend fun sendLease(foreground: Boolean) {
    runCatching {
      val nodes = nodeClient.connectedNodes.await()
      val frame = WearLease(sentAtMs = System.currentTimeMillis(), foreground = foreground)
      val bytes = encodeProto(frame)
      for (node in nodes) {
        messageClient.sendMessage(node.id, WearSyncPaths.LEASE_MESSAGE, bytes).await()
      }
    }
      .onFailure { Log.w(TAG, "sendLease failed", it) }
  }

  private fun handle(path: String, bytes: ByteArray) {
    when {
      path == WearSyncPaths.SETTINGS ->
        decodeProto<WearSettings>(bytes)?.let {
          _settings.value = it
          offline.writeSettings(it)
          // Signed in (or to another instance): the watch may need a token for it.
          scope.launch { direct.requestIfMissing(it) }
        }
      // Only a newer copy replaces the one kept, as in [persistChanged]: the cold read meets every
      // node's copy (a replaced phone's too), in no particular order, and an older one must not
      // become what the next offline start shows.
      path == WearSyncPaths.PINNED ->
        newerThan(
            decodeProto<PinnedCardSet>(bytes),
            offline.readPinned(),
            PinnedCardSet::updatedAtMs,
          )
          ?.let {
            _pinned.value = it
            offline.writePinned(it)
          }
      path == WearSyncPaths.SECTIONS ->
        newerThan(
            decodeProto<PinnedSectionSet>(bytes),
            offline.readSections(),
            PinnedSectionSet::updatedAtMs,
          )
          ?.let {
            _sections.value = it
            offline.writeSections(it)
          }
      // `/wear/slots` (an older phone's widget slot assignments) is ignored: the watch chooses
      // each widget's card itself.
      path == WearSyncPaths.VALUES ->
        decodeProto<LiveValues>(bytes)?.let { live ->
          // A cold DataItem may predate the last streamed delta persisted on disk. Keep the
          // newer snapshot, just as a widget's Data Layer read does.
          valuesNotOlder(live, offline.readValues())?.let {
            _values.value = it.values
            offline.writeValues(it)
          }
        }
      // Read straight from disk by the Wear widgets when they bake.
      path.startsWith(WearSyncPaths.PICTURE_PREFIX) ->
        decodeProto<WearPicture>(bytes)?.let { offline.writePicture(it) }
      path == WearSyncPaths.SLOT_DESIGNS ->
        decodeProto<WearSlotDesigns>(bytes)?.let { designs ->
          newerThan(designs, offline.readSlotDesigns(), WearSlotDesigns::updatedAtMs)?.let {
            offline.writeSlotDesigns(it)
            _slotDesigns.value = it
          }
        }
      path.startsWith(WearSyncPaths.DESIGN_PREFIX) ->
        decodeProto<WearDesignBlob>(bytes)?.let { offline.writeDesign(it) }
      path.startsWith(WearSyncPaths.DASHBOARD_PREFIX) ->
        decodeProto<DashboardData>(bytes)?.let { changed ->
          val kept = _dashboards.value.firstOrNull { it.urlPath == changed.urlPath }
          val dashboard = newerThan(changed, kept, DashboardData::updatedAtMs) ?: return
          val current = _dashboards.value.toMutableList()
          val idx = current.indexOfFirst { it.urlPath == dashboard.urlPath }
          if (idx >= 0) current[idx] = dashboard else current.add(dashboard)
          _dashboards.value = current.sortedBy { it.title }
          offline.writeDashboard(dashboard)
        }
    }
  }

  /** The phone deleted the item at [path] (sign-out, a removed dashboard): drop our copy too. */
  private fun forget(path: String) {
    // Signed out: the watch's own Home Assistant token goes too.
    if (path == WearSyncPaths.SETTINGS || signsOut(listOf(path))) direct.forget()
    // Signed out: no widget may keep drawing the account's cards.
    if (signsOut(listOf(path))) widgetCards.clear()
    when (val item = offline.forget(path)) {
      ForgottenItem.Settings -> _settings.value = WearSettings()
      ForgottenItem.Pinned -> _pinned.value = PinnedCardSet()
      ForgottenItem.Sections -> _sections.value = PinnedSectionSet()
      ForgottenItem.Values -> _values.value = emptyMap()
      is ForgottenItem.Dashboard -> _dashboards.value = _dashboards.value.filterNot(item::matches)
      ForgottenItem.SlotDesigns -> _slotDesigns.value = WearSlotDesigns()
      is ForgottenItem.Picture,
      is ForgottenItem.Design,
      null -> Unit
    }
  }

  companion object {
    private const val TAG = "WearSync"
    const val KEY_PROTO: String = "proto"
  }
}

/**
 * Copies the settings, pinned cards and sections and entity values from the Data Layer into
 * [store], where newer. The settings say which instance the watch may read itself, if any (a widget
 * reading Home Assistant directly must see demo mode or another sign-in as soon as the phone writes
 * it). The Wear widgets call this when they render: [WearSyncRepository] only listens while the
 * watch app runs, and a DataItem persists in the Data Layer whether or not anything was listening
 * when it arrived.
 */
internal suspend fun syncWidgetStateFromDataLayer(context: Context, store: WearOfflineStore) {
  val client = Wearable.getDataClient(context)
  val settingsTime = WearSettings::updatedAtMs
  newerThan(
      dataItem(client, WearSyncPaths.SETTINGS, settingsTime),
      store.readSettings(),
      settingsTime,
    )
    ?.let(store::writeSettings)
  val pinnedTime = PinnedCardSet::updatedAtMs
  newerThan(dataItem(client, WearSyncPaths.PINNED, pinnedTime), store.readPinned(), pinnedTime)
    ?.let(store::writePinned)
  // A widget may show a pinned section's card.
  val sectionsTime = PinnedSectionSet::updatedAtMs
  newerThan(
      dataItem(client, WearSyncPaths.SECTIONS, sectionsTime),
      store.readSections(),
      sectionsTime,
    )
    ?.let(store::writeSections)
  // Stream deltas the running app merged carry their own capture time, so the newer one wins.
  val valuesTime = LiveValues::capturedAtMs
  valuesNotOlder(dataItem(client, WearSyncPaths.VALUES, valuesTime), store.readValues())
    ?.let(store::writeValues)
}

/**
 * Copies the pinned cards' Wear widget designs from the Data Layer into [store]: the index where
 * newer, then the designs it names that [store] lacks. See [syncWidgetStateFromDataLayer].
 */
internal suspend fun syncDesignsFromDataLayer(context: Context, store: WearOfflineStore) {
  val client = Wearable.getDataClient(context)
  val time = WearSlotDesigns::updatedAtMs
  newerThan(dataItem(client, WearSyncPaths.SLOT_DESIGNS, time), store.readSlotDesigns(), time)
    ?.let(store::writeSlotDesigns)
  for (sha in store.readSlotDesigns()?.designs.orEmpty().map { it.sha256 }.distinct()) {
    if (store.readDesign(sha) != null) continue
    dataItem<WearDesignBlob>(client, WearSyncPaths.designPath(sha)) { 0L }?.let(store::writeDesign)
  }
}

/** Copies [entityIds]' pictures from the Data Layer into [store], where newer: see above. */
internal suspend fun syncPicturesFromDataLayer(
  context: Context,
  store: WearOfflineStore,
  entityIds: Collection<String>,
) {
  if (entityIds.isEmpty()) return
  val client = Wearable.getDataClient(context)
  for (entityId in entityIds) {
    val synced = dataItem(client, WearSyncPaths.picturePath(entityId), WearPicture::updatedAtMs)
    newerThan(synced, store.readPicture(entityId), WearPicture::updatedAtMs)
      ?.let(store::writePicture)
  }
}

/** [candidate] when it is newer by [time] than [stored] (or nothing is stored), else null. */
internal fun <T : Any> newerThan(candidate: T?, stored: T?, time: (T) -> Long): T? =
  candidate?.takeIf {
    stored == null || time(it) > time(stored)
  }

/**
 * [candidate] values unless they are older than [stored]. Values captured at the same time are
 * taken: the phone sends a cached snapshot with its fetch time, and when the cards on the watch
 * read more entities it resends that same snapshot with them added.
 */
internal fun valuesNotOlder(candidate: LiveValues?, stored: LiveValues?): LiveValues? =
  candidate?.takeIf {
    stored == null || it.capturedAtMs >= stored.capturedAtMs
  }

/**
 * Ignore delayed stream frames; merge fresh deltas without dropping other entities. A frame from
 * the same capture as [stored] is merged (see [valuesNotOlder]).
 */
internal fun mergeStreamUpdate(stored: LiveValues?, frame: StreamUpdate): LiveValues? {
  if (stored != null && frame.capturedAtMs < stored.capturedAtMs) return null
  val values = stored?.values.orEmpty().toMutableMap()
  frame.deltas.forEach { values[it.entityId] = it.value }
  return LiveValues(values = values, capturedAtMs = frame.capturedAtMs)
}

/**
 * The newest by [time] of the DataItems at [path]: each node that ever wrote one (a replaced phone,
 * say) keeps its own copy, in no particular order. Null if none decode.
 */
private suspend inline fun <reified T> dataItem(
  client: DataClient,
  path: String,
  crossinline time: (T) -> Long,
): T? {
  val uri = android.net.Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(path).build()
  val buffer = runCatching { client.getDataItems(uri).await() }.getOrNull() ?: return null
  return try {
    buffer
      .mapNotNull { item ->
        DataMapItem.fromDataItem(item).dataMap.getByteArray(WearSyncRepository.KEY_PROTO)?.let {
          decodeProto<T>(it)
        }
      }
      .maxByOrNull { time(it) }
  } finally {
    buffer.release()
  }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
  kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value -> cont.resumeWith(Result.success(value)) }
    addOnFailureListener { error -> cont.resumeWith(Result.failure(error)) }
  }
