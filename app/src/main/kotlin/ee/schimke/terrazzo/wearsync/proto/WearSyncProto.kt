package ee.schimke.terrazzo.wearsync.proto

import kotlinx.serialization.Serializable

/**
 * Wear ↔ phone sync schema. The same file is duplicated in the `app`
 * module — both copies must stay byte-for-byte identical so DataItem
 * payloads and MessageClient frames decode on the peer.
 *
 * The values are encoded with `kotlinx.serialization.protobuf.ProtoBuf`,
 * which produces standard proto3 wire bytes — i.e. the same bytes that
 * `protoc`/Wire would write. We choose this approach (rather than
 * generated classes via Wire's Gradle plugin) so the build stays
 * self-contained on AGP 9 / Gradle 9.3, which Wire's plugin doesn't yet
 * recognise as a Kotlin host. The `.proto` file in `src/main/proto/`
 * documents the schema and stays the source of truth.
 *
 * Path conventions (mirrored in [WearSyncPaths]):
 *   - DataClient:
 *     - `/wear/settings`            — [WearSettings]
 *     - `/wear/pinned`              — [PinnedCardSet]
 *     - `/wear/values`              — [LiveValues]
 *     - `/wear/dashboard/<urlPath>` — [DashboardData]
 *   - MessageClient (ephemeral):
 *     - `/wear/lease`   — [WearLease]   (wear → phone heartbeat)
 *     - `/wear/stream`  — [StreamUpdate] (phone → wear delta push)
 */

/** Wear-side settings written by the phone. Drives demo badge and routing. */
@Serializable
data class WearSettings(
    val demoMode: Boolean = false,
    val baseUrl: String = "",
    val updatedAtMs: Long = 0L,
)

/** Card extract phone-side; wear renders a list row from this. */
@Serializable
data class CardSummary(
    val type: String = "",
    val title: String = "",
    val primaryEntityId: String = "",
    val rawJson: String = "",
)

/** One DataStore Proto entry per dashboard, keyed by `urlPath`. */
@Serializable
data class DashboardData(
    val urlPath: String = "",
    val title: String = "",
    val cards: List<CardSummary> = emptyList(),
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
    val cards: List<PinnedCard> = emptyList(),
    val updatedAtMs: Long = 0L,
)

@Serializable
data class PinnedCard(
    val baseUrl: String = "",
    val card: CardSummary = CardSummary(),
)

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
    const val LEASE_MESSAGE: String = "/wear/lease"
    const val STREAM_MESSAGE: String = "/wear/stream"

    /** Encode a urlPath (which may be null for HA's default dashboard) into a DataItem path. */
    fun dashboardPath(urlPath: String?): String =
        DASHBOARD_PREFIX + (urlPath?.takeIf { it.isNotEmpty() }?.replace('/', '_') ?: "_default")

    /**
     * Lease window before phone falls back to batched DataStore writes.
     * Tuned for 30 s of wall-clock; wear sends a heartbeat every 10 s.
     */
    const val LEASE_WINDOW_MS: Long = 30_000L
}
