package ee.schimke.terrazzo.wear.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.data.WearDataLayerRegistry
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.StreamUpdate
import ee.schimke.terrazzo.wearsync.proto.WearLease
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Watch-side counterpart to `MobileWearSyncManager`. Keeps state for:
 *
 *  - [settings]: cross-device flags (demo mode, base url) — sourced from
 *    the `/wear/settings` Proto DataStore entry
 *  - [pinned]: phone's pinned card set
 *  - [dashboards]: one entry per `/wear/dashboard/<urlPath>`
 *  - [values]: latest entity values, merged from the
 *    `/wear/values` DataStore entry (cold reads) and
 *    `/wear/stream` MessageClient deltas (hot path)
 *
 * Streaming deltas are merged into the local [values] state so consumers
 * see one consistent view regardless of cadence.
 */
@OptIn(ExperimentalHorologistApi::class)
class WearSyncRepository(
    context: Context,
    private val scope: CoroutineScope,
) {
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
     * Local on-disk mirror of every proto blob the phone has ever
     * pushed. Read once at [start] so the watch boots from disk when
     * the phone is unreachable; written on every [handle] so the next
     * cold launch sees the latest values without waiting for a sync.
     */
    private val offline: WearOfflineStore = WearOfflineStore(context.applicationContext)

    private val _settings: MutableStateFlow<WearSettings> =
        MutableStateFlow(offline.readSettings() ?: WearSettings())
    val settings: StateFlow<WearSettings> = _settings.asStateFlow()

    private val _pinned: MutableStateFlow<PinnedCardSet> =
        MutableStateFlow(offline.readPinned() ?: PinnedCardSet())
    val pinned: StateFlow<PinnedCardSet> = _pinned.asStateFlow()

    private val _dashboards: MutableStateFlow<List<DashboardData>> =
        MutableStateFlow(offline.readAllDashboards().sortedBy { it.title })
    val dashboards: StateFlow<List<DashboardData>> = _dashboards.asStateFlow()

    private val _values: MutableStateFlow<Map<String, EntityValue>> =
        MutableStateFlow(offline.readValues()?.values ?: emptyMap())
    val values: StateFlow<Map<String, EntityValue>> = _values.asStateFlow()

    private val dataListener = DataClient.OnDataChangedListener { events: DataEventBuffer ->
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            val mapItem = DataMapItem.fromDataItem(item)
            val bytes = mapItem.dataMap.getByteArray(KEY_PROTO) ?: continue
            handle(item.uri.path.orEmpty(), bytes)
        }
        events.release()
    }

    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        if (event.path != WearSyncPaths.STREAM_MESSAGE) return@OnMessageReceivedListener
        val frame: StreamUpdate = decodeProto(event.data) ?: return@OnMessageReceivedListener
        val merged = _values.value.toMutableMap()
        for (delta in frame.deltas) {
            merged[delta.entityId] = delta.value
        }
        _values.value = merged
        // Persist the merged snapshot so a process restart resumes from
        // the latest delta-updated state, not the last full DataItem.
        offline.writeValues(LiveValues(values = merged, capturedAtMs = frame.capturedAtMs))
    }

    fun start() {
        dataClient.addListener(dataListener)
        messageClient.addListener(messageListener)
        // Cold-read all known paths so the UI lights up immediately on
        // launch instead of waiting for the next change event.
        scope.launch {
            runCatching { dataClient.dataItems.await() }.getOrNull()?.let { buffer ->
                for (i in 0 until buffer.count) {
                    val item = buffer[i]
                    val mapItem = DataMapItem.fromDataItem(item)
                    val bytes = mapItem.dataMap.getByteArray(KEY_PROTO) ?: continue
                    handle(item.uri.path.orEmpty(), bytes)
                }
                buffer.release()
            }
        }
    }

    fun stop() {
        runCatching { dataClient.removeListener(dataListener) }
        runCatching { messageClient.removeListener(messageListener) }
    }

    /**
     * Push a heartbeat to the phone. Wear's lifecycle controller calls
     * this every [WearLeaseController.HEARTBEAT_MS] while the activity
     * is foreground; phone treats arrival time as the lease window.
     */
    suspend fun sendLease(foreground: Boolean) {
        runCatching {
            val nodes = nodeClient.connectedNodes.await()
            val frame = WearLease(
                sentAtMs = System.currentTimeMillis(),
                foreground = foreground,
            )
            val bytes = encodeProto(frame)
            for (node in nodes) {
                messageClient.sendMessage(node.id, WearSyncPaths.LEASE_MESSAGE, bytes).await()
            }
        }.onFailure { Log.w(TAG, "sendLease failed", it) }
    }

    private fun handle(path: String, bytes: ByteArray) {
        when {
            path == WearSyncPaths.SETTINGS ->
                decodeProto<WearSettings>(bytes)?.let {
                    _settings.value = it
                    offline.writeSettings(it)
                }
            path == WearSyncPaths.PINNED ->
                decodeProto<PinnedCardSet>(bytes)?.let {
                    _pinned.value = it
                    offline.writePinned(it)
                }
            path == WearSyncPaths.VALUES ->
                decodeProto<LiveValues>(bytes)?.let { live ->
                    // Cold-read overwrites entirely; subsequent stream
                    // deltas will merge from this baseline.
                    _values.value = live.values
                    offline.writeValues(live)
                }
            path.startsWith(WearSyncPaths.DASHBOARD_PREFIX) ->
                decodeProto<DashboardData>(bytes)?.let { dashboard ->
                    val current = _dashboards.value.toMutableList()
                    val idx = current.indexOfFirst { it.urlPath == dashboard.urlPath }
                    if (idx >= 0) current[idx] = dashboard else current.add(dashboard)
                    _dashboards.value = current.sortedBy { it.title }
                    offline.writeDashboard(dashboard)
                }
        }
    }

    companion object {
        private const val TAG = "WearSync"
        const val KEY_PROTO: String = "proto"
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        addOnSuccessListener { value -> cont.resumeWith(Result.success(value)) }
        addOnFailureListener { error -> cont.resumeWith(Result.failure(error)) }
    }
