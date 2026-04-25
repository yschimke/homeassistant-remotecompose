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
import ee.schimke.terrazzo.wearsync.proto.CardDoc
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
 * Watch-side counterpart to `MobileWearSyncManager`. State buckets:
 *
 *  - [settings]: cross-device flags (demo mode, base url, theme style)
 *  - [pinned]: phone's pinned card refs
 *  - [dashboards]: list of dashboards (refs only)
 *  - [cardDocs]: id → pre-baked RemoteCompose document (the `.rc`
 *    bytes phone published per card; the wear UI plays these directly
 *    via `RemoteDocumentPlayer`)
 *  - [values]: latest entity values, merged from `/wear/values`
 *    DataStore reads and `/wear/stream` MessageClient deltas
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

    private val _settings: MutableStateFlow<WearSettings> = MutableStateFlow(WearSettings())
    val settings: StateFlow<WearSettings> = _settings.asStateFlow()

    private val _pinned: MutableStateFlow<PinnedCardSet> = MutableStateFlow(PinnedCardSet())
    val pinned: StateFlow<PinnedCardSet> = _pinned.asStateFlow()

    private val _dashboards: MutableStateFlow<List<DashboardData>> = MutableStateFlow(emptyList())
    val dashboards: StateFlow<List<DashboardData>> = _dashboards.asStateFlow()

    private val _cardDocs: MutableStateFlow<Map<String, CardDoc>> = MutableStateFlow(emptyMap())
    val cardDocs: StateFlow<Map<String, CardDoc>> = _cardDocs.asStateFlow()

    private val _values: MutableStateFlow<Map<String, EntityValue>> = MutableStateFlow(emptyMap())
    val values: StateFlow<Map<String, EntityValue>> = _values.asStateFlow()

    private val dataListener = DataClient.OnDataChangedListener { events: DataEventBuffer ->
        for (event in events) {
            val item = event.dataItem
            val path = item.uri.path.orEmpty()
            when (event.type) {
                DataEvent.TYPE_CHANGED -> {
                    val mapItem = DataMapItem.fromDataItem(item)
                    val bytes = mapItem.dataMap.getByteArray(KEY_PROTO) ?: continue
                    handle(path, bytes)
                }
                DataEvent.TYPE_DELETED -> handleDelete(path)
            }
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
                decodeProto<WearSettings>(bytes)?.let { _settings.value = it }
            path == WearSyncPaths.PINNED ->
                decodeProto<PinnedCardSet>(bytes)?.let { _pinned.value = it }
            path == WearSyncPaths.VALUES ->
                decodeProto<LiveValues>(bytes)?.let { live -> _values.value = live.values }
            path.startsWith(WearSyncPaths.DASHBOARD_PREFIX) ->
                decodeProto<DashboardData>(bytes)?.let { dashboard ->
                    val current = _dashboards.value.toMutableList()
                    val idx = current.indexOfFirst { it.urlPath == dashboard.urlPath }
                    if (idx >= 0) current[idx] = dashboard else current.add(dashboard)
                    _dashboards.value = current.sortedBy { it.title }
                }
            path.startsWith(WearSyncPaths.CARD_PREFIX) ->
                decodeProto<CardDoc>(bytes)?.let { doc ->
                    if (doc.id.isNotEmpty()) {
                        _cardDocs.value = _cardDocs.value + (doc.id to doc)
                    }
                }
        }
    }

    private fun handleDelete(path: String) {
        if (!path.startsWith(WearSyncPaths.CARD_PREFIX)) return
        val id = path.removePrefix(WearSyncPaths.CARD_PREFIX)
        if (id.isEmpty()) return
        _cardDocs.value = _cardDocs.value - id
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
