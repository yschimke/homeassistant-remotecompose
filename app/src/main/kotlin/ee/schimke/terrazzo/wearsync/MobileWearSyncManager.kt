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
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.prefs.ThemePref
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.widget.WidgetStore
import ee.schimke.terrazzo.wearsync.proto.CardDoc
import ee.schimke.terrazzo.wearsync.proto.CardRef
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.EntityDelta
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import ee.schimke.terrazzo.wearsync.proto.PinnedCardRef
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phone-side sync engine. Mirrors:
 *
 *  - [PreferencesStore.demoMode] + [PreferencesStore.themeStyle] →
 *    `/wear/settings`
 *  - The current [HaSession]'s dashboards → `/wear/dashboard/<urlPath>`
 *    (one DataItem per dashboard, refs only — the actual `.rc` bytes
 *    live at `/wear/card/<id>`)
 *  - The card-converted RemoteCompose document → `/wear/card/<id>`
 *    (one DataItem per card, baked with the wear-side **dark** theme
 *    derived from [PreferencesStore.themeStyle])
 *  - The current snapshot → `/wear/values` (DataStore-style at the wall
 *    refresh interval) **or** `/wear/stream` (MessageClient ephemeral
 *    deltas) when wear holds an active lease
 *  - [WidgetStore.installed] → `/wear/pinned` + per-pinned card docs
 *
 * Every publish is themed — the wear app has no local theme picker;
 * the `themeStyle` field on the published `WearSettings` is what drives
 * the watch's chrome and what the phone uses for its
 * `captureThemedCardDocument` calls (always with `darkTheme = true`).
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
        WearDataLayerRegistry.fromContext(
            application = context.applicationContext as android.app.Application,
            coroutineScope = managerScope,
        )
    }

    private val cardRegistry: CardRegistry = defaultRegistry()

    private val sessionState: MutableStateFlow<HaSession?> = MutableStateFlow(null)
    private val leaseState: MutableStateFlow<Long> = MutableStateFlow(0L)
    private lateinit var managerScope: CoroutineScope
    private var streamJob: Job? = null
    private var lastSnapshot: Map<String, EntityValue> = emptyMap()
    /** Tracks the doc id we've already published this session, so we can
     *  delete cards that go away when a dashboard is reshaped. */
    private val publishedCardIds: MutableSet<String> = mutableSetOf()

    private val leaseListener = MessageClient.OnMessageReceivedListener { event ->
        if (event.path != WearSyncPaths.LEASE_MESSAGE) return@OnMessageReceivedListener
        val now = System.currentTimeMillis()
        val lease: WearLease = decodeProto<WearLease>(event.data) ?: WearLease(sentAtMs = now)
        leaseState.value = lease.sentAtMs.takeIf { it > 0 } ?: now
        managerScope.launch { statsStore.recordLease(now) }
    }

    fun setSession(session: HaSession?) {
        sessionState.value = session
    }

    val streamActive: StateFlow<Boolean> by lazy {
        leaseState.map { isLeaseFresh(it) }
            .stateIn(managerScope, SharingStarted.Eagerly, false)
    }

    fun start(
        scope: CoroutineScope,
        prefs: PreferencesStore,
        widgetStore: WidgetStore,
    ) {
        managerScope = scope
        messageClient.addListener(leaseListener)

        // Settings (demo flag + base url + theme) → /wear/settings.
        scope.launch {
            combine(prefs.demoMode, prefs.themeStyle, sessionState) { demo, theme, session ->
                WearSettings(
                    demoMode = demo,
                    baseUrl = session?.baseUrl.orEmpty(),
                    themeStyle = theme.name,
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
                .distinctUntilChanged()
                .collect { writeDataItem(WearSyncPaths.SETTINGS, encodeProto(it)) }
        }

        // Pinned widgets → /wear/pinned + a CardDoc per pinned card. We
        // republish on theme change too so the watch's pinned cards
        // re-skin together with the phone's home screen widgets.
        scope.launch {
            combine(widgetStore.installed, prefs.themeStyle) { entries, theme -> entries to theme }
                .distinctUntilChanged()
                .collect { (entries, theme) ->
                    val style = theme.toStyle()
                    publishPinned(entries, style)
                }
        }

        // Session snapshot pump. Re-driven on every session or theme
        // change so the demo session and live session each get their
        // own loop, and a theme switch re-bakes every card.
        scope.launch {
            combine(sessionState, prefs.themeStyle, widgetStore.installed) { s, t, w ->
                Triple(s, t, w)
            }.collectLatest { (session, theme, pinned) ->
                streamJob?.cancel()
                streamJob = null
                if (session == null) {
                    // Sign-out: clean up any per-card docs we left lying around.
                    pruneCards(emptySet())
                    return@collectLatest
                }
                val style = theme.toStyle()
                publishDashboards(session, style)
                streamJob = scope.launch { sessionPump(session, style, pinned.isNotEmpty()) }
            }
        }
    }

    fun stop() {
        runCatching { messageClient.removeListener(leaseListener) }
        streamJob?.cancel()
    }

    private fun isLeaseFresh(at: Long): Boolean {
        if (at <= 0L) return false
        val now = System.currentTimeMillis()
        return now - at <= WearSyncPaths.LEASE_WINDOW_MS
    }

    private suspend fun publishDashboards(session: HaSession, style: ThemeStyle) {
        runCatching {
            val summaries = session.listDashboards()
            val keepIds = mutableSetOf<String>()
            for (summary in summaries) {
                val (dashboard, snapshot) = session.loadDashboard(summary.urlPath)
                val cards = dashboard.views.flatMap { it.cards }
                val refs = mutableListOf<CardRef>()
                cards.forEachIndexed { idx, card ->
                    val id = dashboardCardId(summary.urlPath, idx)
                    val ref = card.toRef(id, snapshot.states)
                    refs.add(ref)
                    publishCardDoc(id, card, snapshot, style)
                    keepIds.add(id)
                }
                val data = DashboardData(
                    urlPath = summary.urlPath ?: "",
                    title = dashboard.title ?: summary.title,
                    cards = refs,
                    updatedAtMs = System.currentTimeMillis(),
                )
                writeDataItem(WearSyncPaths.dashboardPath(summary.urlPath), encodeProto(data))
            }
            // Pinned cards stay regardless of the dashboard set, so we
            // intersect with what publishPinned wrote.
            pruneCards(keepIds + publishedCardIds.filter { it.startsWith(PINNED_PREFIX) })
        }.onFailure { Log.w(TAG, "publishDashboards failed", it) }
    }

    private suspend fun publishPinned(entries: List<WidgetStore.Entry>, style: ThemeStyle) {
        val refs = mutableListOf<PinnedCardRef>()
        val keepIds = mutableSetOf<String>()
        for (entry in entries) {
            val id = pinnedCardId(entry.widgetId)
            val ref = PinnedCardRef(
                id = id,
                baseUrl = entry.baseUrl,
                title = entry.card.titleHint(),
                primaryEntityId = entry.card.primaryEntity(),
                type = entry.card.type,
            )
            refs.add(ref)
            // Pinned cards capture against an empty snapshot — phone
            // doesn't have authoritative state for the card without the
            // session loaded. Live-mode pinned cards get fresher bytes
            // when sessionPump kicks them on next snapshot.
            publishCardDoc(id, entry.card, HaSnapshot(), style)
            keepIds.add(id)
        }
        val set = PinnedCardSet(cards = refs, updatedAtMs = System.currentTimeMillis())
        writeDataItem(WearSyncPaths.PINNED, encodeProto(set))

        // Drop pinned card docs that no longer exist; preserve dashboard ones.
        val keep = keepIds + publishedCardIds.filter { !it.startsWith(PINNED_PREFIX) }
        pruneCards(keep)
    }

    private suspend fun publishCardDoc(
        id: String,
        card: CardConfig,
        snapshot: HaSnapshot,
        style: ThemeStyle,
    ) {
        runCatching {
            val captured = captureThemedCardDocument(
                context = context,
                registry = cardRegistry,
                card = card,
                snapshot = snapshot,
                // Always dark on the watch — even if the phone's UI is
                // showing light mode, wear faces are dark surfaces.
                haTheme = haThemeFor(style, darkTheme = true),
                widthPx = WEAR_DOC_WIDTH_PX,
            )
            val doc = CardDoc(
                id = id,
                rcBytes = captured.bytes,
                widthPx = captured.widthPx,
                heightPx = captured.heightPx,
                updatedAtMs = System.currentTimeMillis(),
            )
            writeDataItem(WearSyncPaths.cardPath(id), encodeProto(doc))
            publishedCardIds.add(id)
        }.onFailure { Log.w(TAG, "publishCardDoc $id failed", it) }
    }

    /** Delete `/wear/card/<id>` for any id we've published that isn't in [keep]. */
    private suspend fun pruneCards(keep: Set<String>) {
        val drop = publishedCardIds - keep
        for (id in drop) {
            runCatching {
                dataClient.deleteDataItems(
                    android.net.Uri.Builder().scheme("wear").path(WearSyncPaths.cardPath(id)).build(),
                ).await()
            }
        }
        publishedCardIds.removeAll(drop)
    }

    /**
     * Fetches snapshots from [session]; streams via MessageClient
     * (deltas) when wear has a lease or pinned cards exist, otherwise
     * batches into the DataStore Proto entry. Live sessions that don't
     * expose a refresh interval fall back to a 30 s heartbeat.
     */
    private suspend fun sessionPump(session: HaSession, style: ThemeStyle, hasPinned: Boolean) {
        val cadence = session.refreshIntervalMillis ?: 30_000L
        lastSnapshot = emptyMap()
        while (true) {
            val snapshot = runCatching { session.loadDashboard(null).second }.getOrNull()
            if (snapshot != null) {
                val current = snapshot.states.mapValues { (_, state) -> state.toEntityValue() }
                val streaming = hasPinned || isLeaseFresh(leaseState.value)
                if (streaming) pushStream(current) else pushDataItem(current)
            }
            delay(cadence)
            // (Re-)bake docs is left to publishDashboards — covered by
            // theme/session change. Live values flow over the values
            // path so wear's RemoteDocumentPlayer stays current via the
            // values map, not by re-baking each tick.
            @Suppress("UNUSED_EXPRESSION") style
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
        /**
         * Width-in-px target for wear-side documents. 384 px sits at
         * the small-round face's natural width and gives the converter
         * a typical phone tile size to bake against; the player scales
         * the doc to whatever the watch actually has.
         */
        const val WEAR_DOC_WIDTH_PX: Int = 384
        const val PINNED_PREFIX: String = "pin_"
        fun dashboardCardId(urlPath: String?, index: Int): String {
            val safe = urlPath?.takeIf { it.isNotEmpty() }?.replace(Regex("[^A-Za-z0-9]"), "_")
                ?: "default"
            return "dash_${safe}_$index"
        }
        fun pinnedCardId(widgetId: Int): String = "$PINNED_PREFIX$widgetId"
    }
}

private fun ThemePref.toStyle(): ThemeStyle = when (this) {
    ThemePref.Material3 -> ThemeStyle.Material3
    ThemePref.TerrazzoHome -> ThemeStyle.TerrazzoHome
    ThemePref.TerrazzoMushroom -> ThemeStyle.TerrazzoMushroom
    ThemePref.TerrazzoMinimalist -> ThemeStyle.TerrazzoMinimalist
    ThemePref.TerrazzoKiosk -> ThemeStyle.TerrazzoKiosk
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

private fun CardConfig.toRef(id: String, states: Map<String, EntityState>): CardRef {
    val title = titleHint()
    val primary = primaryEntity()
    val resolvedTitle = states[primary]?.attributes?.get("friendly_name")
        ?.jsonPrimitive?.contentOrNull ?: title
    return CardRef(
        id = id,
        title = resolvedTitle,
        primaryEntityId = primary,
        type = type,
    )
}

private fun CardConfig.titleHint(): String =
    raw["title"]?.jsonPrimitive?.contentOrNull
        ?: raw["heading"]?.jsonPrimitive?.contentOrNull
        ?: raw["name"]?.jsonPrimitive?.contentOrNull
        ?: type

private fun CardConfig.primaryEntity(): String =
    raw["entity"]?.jsonPrimitive?.contentOrNull
        ?: raw["entities"]?.jsonArray?.firstOrNull()?.let {
            (it as? JsonPrimitive)?.contentOrNull
                ?: (it as? JsonObject)?.get("entity")?.jsonPrimitive?.contentOrNull
        }
        ?: ""

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        addOnSuccessListener { value -> cont.resumeWith(Result.success(value)) }
        addOnFailureListener { error -> cont.resumeWith(Result.failure(error)) }
    }
