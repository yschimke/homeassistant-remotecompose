package ee.schimke.terrazzo.wearsync.proto

import kotlinx.serialization.Serializable

/**
 * Wear ↔ phone sync schema. Mirrored on both modules; encoded as proto3
 * wire bytes via `kotlinx-serialization-protobuf` so the bytes are
 * compatible with anything that speaks proto.
 *
 * Each card lives in its own DataItem at `/wear/card/<id>` carrying the
 * pre-baked RemoteCompose document — the watch only needs the
 * RemoteComposePlayer plus the card id; it never sees raw HA JSON.
 *
 * Path conventions (mirrored in [WearSyncPaths]):
 *   - DataClient:
 *     - `/wear/settings`             — [WearSettings]
 *     - `/wear/pinned`               — [PinnedCardSet] (refs only)
 *     - `/wear/values`               — [LiveValues]
 *     - `/wear/dashboard/<urlPath>`  — [DashboardData] (refs only)
 *     - `/wear/card/<id>`            — [CardDoc] (the actual `.rc` bytes)
 *   - MessageClient (ephemeral):
 *     - `/wear/lease`   — [WearLease]   (wear → phone heartbeat)
 *     - `/wear/stream`  — [StreamUpdate] (phone → wear delta push)
 */

/**
 * Cross-device wear settings written by phone. Includes the chosen
 * theme name + dark-mode flag so the watch can re-skin its chrome
 * to match — wear has no local theme picker; the phone owns the
 * preference and writes it here on every change.
 */
@Serializable
data class WearSettings(
    val demoMode: Boolean = false,
    val baseUrl: String = "",
    /**
     * Phone's `ThemePref.name`. Watch maps this to a wear-side palette
     * (always rendered dark on the watch) so the chrome behind the
     * RemoteCompose player stays in sync with the phone's choice.
     */
    val themeStyle: String = "",
    val updatedAtMs: Long = 0L,
)

/**
 * Card pointer — what `DashboardData` and `PinnedCardSet` carry. The
 * actual RemoteCompose bytes live in their own DataItem at
 * `WearSyncPaths.cardPath(id)`; watch resolves the ref to a doc on
 * demand.
 */
@Serializable
data class CardRef(
    val id: String = "",
    val title: String = "",
    val primaryEntityId: String = "",
    val type: String = "",
)

/** One DataStore Proto entry per dashboard, keyed by `urlPath`. */
@Serializable
data class DashboardData(
    val urlPath: String = "",
    val title: String = "",
    val cards: List<CardRef> = emptyList(),
    val updatedAtMs: Long = 0L,
)

/** Single DataStore entry holding the latest entity values. */
@Serializable
data class LiveValues(
    val values: Map<String, EntityValue> = emptyMap(),
    val capturedAtMs: Long = 0L,
)

@Serializable
data class EntityValue(
    val state: String = "",
    val friendlyName: String = "",
    val unit: String = "",
    val deviceClass: String = "",
)

/** MessageClient stream frame — only entities that changed. */
@Serializable
data class StreamUpdate(
    val deltas: List<EntityDelta> = emptyList(),
    val capturedAtMs: Long = 0L,
)

@Serializable
data class EntityDelta(
    val entityId: String = "",
    val value: EntityValue = EntityValue(),
)

/** Mirror of phone's WidgetStore — drives wear's home screen. */
@Serializable
data class PinnedCardSet(
    val cards: List<PinnedCardRef> = emptyList(),
    val updatedAtMs: Long = 0L,
)

@Serializable
data class PinnedCardRef(
    val id: String = "",
    val baseUrl: String = "",
    val title: String = "",
    val primaryEntityId: String = "",
    val type: String = "",
)

/**
 * The actual `.rc` document for a single card. Phone bakes one per
 * card with the wear-side dark theme already applied; watch plays the
 * bytes via `RemoteDocumentPlayer`.
 *
 * `widthPx`/`heightPx` are the document's natural pixel size at capture
 * time — the player honours these as intrinsic content size so the
 * card scales sensibly on round / rectangular faces.
 */
@Serializable
data class CardDoc(
    val id: String = "",
    val rcBytes: ByteArray = byteArrayOf(),
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val updatedAtMs: Long = 0L,
) {
    override fun equals(other: Any?): Boolean =
        other is CardDoc && id == other.id && widthPx == other.widthPx &&
            heightPx == other.heightPx && updatedAtMs == other.updatedAtMs &&
            rcBytes.contentEquals(other.rcBytes)
    override fun hashCode(): Int =
        ((id.hashCode() * 31 + widthPx) * 31 + heightPx) * 31 + rcBytes.contentHashCode()
}

/**
 * Heartbeat sent from wear → phone over MessageClient. Phone treats the
 * arrival time as a lease window; while a recent lease is active phone
 * pushes [StreamUpdate]s, otherwise it batches into [LiveValues].
 */
@Serializable
data class WearLease(
    val sentAtMs: Long = 0L,
    val foreground: Boolean = false,
)

/**
 * Phone-local diagnostics. Stored at `terrazzo_sync_stats.pb` and
 * surfaced in Settings → Diagnostics.
 */
@Serializable
data class SyncStats(
    val datastoreWrites: Long = 0L,
    val messageSends: Long = 0L,
    val lastLeaseAtMs: Long = 0L,
    val lastWriteAtMs: Long = 0L,
    val lastMessageAtMs: Long = 0L,
    /** Bounded ring (last 32 send timestamps in ms) for rolling-window frequency. */
    val recentSendMs: List<Long> = emptyList(),
)

/** Path constants — keep mirrored across modules. */
object WearSyncPaths {
    const val SETTINGS: String = "/wear/settings"
    const val PINNED: String = "/wear/pinned"
    const val VALUES: String = "/wear/values"
    const val DASHBOARD_PREFIX: String = "/wear/dashboard/"
    const val CARD_PREFIX: String = "/wear/card/"
    const val LEASE_MESSAGE: String = "/wear/lease"
    const val STREAM_MESSAGE: String = "/wear/stream"

    /** Encode a urlPath (which may be null for HA's default dashboard) into a DataItem path. */
    fun dashboardPath(urlPath: String?): String =
        DASHBOARD_PREFIX + (urlPath?.takeIf { it.isNotEmpty() }?.replace('/', '_') ?: "_default")

    /** DataItem path for a single card's `.rc` document. */
    fun cardPath(id: String): String = CARD_PREFIX + id

    /**
     * Lease window before phone falls back to batched DataStore writes.
     * Tuned for 30 s of wall-clock; wear sends a heartbeat every 10 s.
     */
    const val LEASE_WINDOW_MS: Long = 30_000L
}
