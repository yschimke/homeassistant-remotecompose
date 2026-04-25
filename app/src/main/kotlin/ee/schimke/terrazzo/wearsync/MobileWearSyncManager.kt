package ee.schimke.terrazzo.wearsync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.horologist.data.WearDataLayerRegistry
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.widget.WidgetStore
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.EntityDelta
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import ee.schimke.terrazzo.wearsync.proto.PinnedCard
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.StreamUpdate
import ee.schimke.terrazzo.wearsync.proto.WearLease
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
 *  - [PreferencesStore.demoMode] → `/wear/settings`
 *  - The current [HaSession]'s dashboards → `/wear/dashboard/<urlPath>`
 *    (one DataItem per dashboard, on first session-bind)
 *  - The current snapshot → `/wear/values` (DataStore-style at the wall
 *    refresh interval) **or** `/wear/stream` (MessageClient ephemeral
 *    deltas) when wear holds an active lease
 *  - [WidgetStore.installed] → `/wear/pinned`
 *
 * Lease is tracked via MessageClient frames at `/wear/lease`: as long as
 * a recent lease arrived (or pinned cards exist) the manager streams
 * deltas at the live cadence; otherwise it batches into the
 * `/wear/values` DataItem so wear reads cold are still useful while
 * keeping radio-time low when the watch isn't engaged.
 *
 * Diagnostics: every write / send updates [MobileSyncStatsStore].
 */
class MobileWearSyncManager(
    private val context: Context,
    private val statsStore: MobileSyncStatsStore,
) {

    private val dataClient: DataClient by lazy { Wearable.getDataClient(context) }
    private val messageClient: MessageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient: NodeClient by lazy { Wearable.getNodeClient(context) }

    /** Lifted Horologist registry so future code can use `protoFlow`/`protoDataStore`. */
    @OptIn(com.google.android.horologist.annotations.ExperimentalHorologistApi::class)
    @Suppress("unused")
    private val registry: WearDataLayerRegistry by lazy {
        WearDataLayerRegistry.fromContext(application = context.applicationContext as android.app.Application, coroutineScope = managerScope)
    }

    private val sessionState: MutableStateFlow<HaSession?> = MutableStateFlow(null)
    private val leaseState: MutableStateFlow<Long> = MutableStateFlow(0L)
    private lateinit var managerScope: CoroutineScope
    private var streamJob: Job? = null
    private var lastSnapshot: Map<String, EntityValue> = emptyMap()

    private val leaseListener = MessageClient.OnMessageReceivedListener { event ->
        if (event.path != WearSyncPaths.LEASE_MESSAGE) return@OnMessageReceivedListener
        val now = System.currentTimeMillis()
        val lease: WearLease = decodeProto<WearLease>(event.data) ?: WearLease(sentAtMs = now)
        leaseState.value = lease.sentAtMs.takeIf { it > 0 } ?: now
        managerScope.launch { statsStore.recordLease(now) }
    }

    /** Update the active session whenever login/demo state changes. */
    fun setSession(session: HaSession?) {
        sessionState.value = session
    }

    /**
     * Whether the wear node currently has an active lease. Combined
     * with pinned-cards count to decide between streaming and batched
     * DataStore writes.
     */
    val streamActive: StateFlow<Boolean> by lazy {
        leaseState.map { isLeaseFresh(it) }
            .stateIn(managerScope, SharingStarted.Eagerly, false)
    }

    /**
     * Wires the manager to its data sources. Call once from
     * Application.onCreate. Subsequent session changes use [setSession].
     */
    fun start(
        scope: CoroutineScope,
        prefs: PreferencesStore,
        widgetStore: WidgetStore,
    ) {
        managerScope = scope
        messageClient.addListener(leaseListener)

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

        // Pinned widgets → /wear/pinned.
        scope.launch {
            widgetStore.installed
                .map { entries ->
                    PinnedCardSet(
                        cards = entries.map { entry ->
                            PinnedCard(
                                baseUrl = entry.baseUrl,
                                card = entry.card.toSummary(),
                            )
                        },
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
                .distinctUntilChanged()
                .collect { writeDataItem(WearSyncPaths.PINNED, encodeProto(it)) }
        }

        // Session snapshot pump. Re-driven on every session change so the
        // demo session and live session each get their own loop.
        scope.launch {
            combine(sessionState, widgetStore.installed) { s, pinned -> s to pinned }
                .collectLatest { (session, pinned) ->
                    streamJob?.cancel()
                    streamJob = null
                    if (session == null) return@collectLatest
                    publishDashboards(session)
                    streamJob = scope.launch { sessionPump(session, pinned.isNotEmpty()) }
                }
        }
    }

    /** Stops listening; safe to call from Application.onTerminate (rare). */
    fun stop() {
        runCatching { messageClient.removeListener(leaseListener) }
        streamJob?.cancel()
    }

    /**
     * Wear → phone path. Ephemeral message delivered if wear-side
     * activity is foreground. We record the lease arrival as the
     * manager's window source.
     */
    @Suppress("unused")
    private fun isLeaseFresh(at: Long): Boolean {
        if (at <= 0L) return false
        val now = System.currentTimeMillis()
        return now - at <= WearSyncPaths.LEASE_WINDOW_MS
    }

    private suspend fun publishDashboards(session: HaSession) {
        runCatching {
            val summaries = session.listDashboards()
            for (summary in summaries) {
                val (dashboard, snapshot) = session.loadDashboard(summary.urlPath)
                val cards = dashboard.views.flatMap { it.cards }
                val data = DashboardData(
                    urlPath = summary.urlPath ?: "",
                    title = dashboard.title ?: summary.title,
                    cards = cards.map { it.toSummary(snapshot.states) },
                    updatedAtMs = System.currentTimeMillis(),
                )
                writeDataItem(WearSyncPaths.dashboardPath(summary.urlPath), encodeProto(data))
            }
        }.onFailure { Log.w(TAG, "publishDashboards failed", it) }
    }

    /**
     * Repeatedly fetches snapshots from [session]; streams via
     * MessageClient (deltas) when wear has a lease or pinned cards
     * exist, otherwise batches into the DataStore-Proto entry. Live
     * sessions that don't expose a refresh interval fall back to a
     * 30 s heartbeat so values aren't infinitely stale.
     */
    private suspend fun sessionPump(session: HaSession, hasPinned: Boolean) {
        val cadence = session.refreshIntervalMillis ?: 30_000L
        // Reset snapshot baseline so the first push is a full set.
        lastSnapshot = emptyMap()
        while (true) {
            val snapshot = runCatching { session.loadDashboard(null).second }.getOrNull()
            if (snapshot != null) {
                val current = snapshot.states.mapValues { (_, state) -> state.toEntityValue() }
                val streaming = hasPinned || isLeaseFresh(leaseState.value)
                if (streaming) pushStream(current) else pushDataItem(current)
            }
            delay(cadence)
        }
    }

    private suspend fun pushDataItem(values: Map<String, EntityValue>) {
        val now = System.currentTimeMillis()
        val payload = LiveValues(values = values, capturedAtMs = now)
        writeDataItem(WearSyncPaths.VALUES, encodeProto(payload))
        lastSnapshot = values
    }

    private suspend fun pushStream(values: Map<String, EntityValue>) {
        val deltas = values.entries
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
        }.onFailure { Log.w(TAG, "pushStream failed", it) }
        lastSnapshot = values
    }

    private suspend fun writeDataItem(path: String, bytes: ByteArray) {
        runCatching {
            val request = PutDataMapRequest.create(path).apply {
                dataMap.putByteArray(KEY_PROTO, bytes)
                dataMap.putLong(KEY_TS, System.currentTimeMillis())
            }
            dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
            statsStore.recordWrite(System.currentTimeMillis())
        }.onFailure { Log.w(TAG, "writeDataItem $path failed", it) }
    }

    companion object {
        private const val TAG = "WearSync"
        const val KEY_PROTO: String = "proto"
        const val KEY_TS: String = "ts"
    }
}

private fun EntityState.toEntityValue(): EntityValue {
    val attrs = attributes
    return EntityValue(
        state = state,
        friendlyName = attrs["friendly_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        unit = attrs["unit_of_measurement"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        deviceClass = attrs["device_class"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
}

private fun CardConfig.toSummary(states: Map<String, EntityState> = emptyMap()): CardSummary {
    val raw = this.raw
    val title = raw["title"]?.jsonPrimitive?.contentOrNull
        ?: raw["heading"]?.jsonPrimitive?.contentOrNull
        ?: raw["name"]?.jsonPrimitive?.contentOrNull
        ?: this.type
    val primary = raw["entity"]?.jsonPrimitive?.contentOrNull
        ?: raw["entities"]?.jsonArray?.firstOrNull()?.let {
            (it as? JsonPrimitive)?.contentOrNull
                ?: (it as? JsonObject)?.get("entity")?.jsonPrimitive?.contentOrNull
        }
        ?: ""
    val resolvedTitle = states[primary]?.attributes?.get("friendly_name")
        ?.jsonPrimitive?.contentOrNull ?: title
    return CardSummary(
        type = this.type,
        title = resolvedTitle,
        primaryEntityId = primary,
        rawJson = json.encodeToString(JsonObject.serializer(), raw),
    )
}

private val json = Json { ignoreUnknownKeys = true }

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        addOnSuccessListener { value -> cont.resumeWith(Result.success(value)) }
        addOnFailureListener { error -> cont.resumeWith(Result.failure(error)) }
    }
