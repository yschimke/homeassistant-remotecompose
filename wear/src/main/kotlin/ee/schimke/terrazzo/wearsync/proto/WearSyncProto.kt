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
 *     - `/wear/sections`            — [PinnedSectionSet]
 *     - `/wear/slots`               — [WearWidgetSlots]
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

/**
 * User-curated pinned cards. Top-level destinations on Wear and the
 * source of truth for [WearWidgetSlots] assignments (slots reference
 * cards by [PinnedCard.cardKey]).
 */
@Serializable
data class PinnedCardSet(
    val cards: List<PinnedCard> = emptyList(),
    val updatedAtMs: Long = 0L,
)

@Serializable
data class PinnedCard(
    val baseUrl: String = "",
    val card: CardSummary = CardSummary(),
    /** Stable id chosen at pin time; referenced by [WidgetSlot.cardKey]. */
    val cardKey: String = "",
    /** Position in the unified Wear top-level ordering. */
    val orderIndex: Int = 0,
)

/** User-pinned dashboard sections, each becomes a Wear nav destination. */
@Serializable
data class PinnedSectionSet(
    val sections: List<PinnedSection> = emptyList(),
    val updatedAtMs: Long = 0L,
)

@Serializable
data class PinnedSection(
    val baseUrl: String = "",
    val dashboardUrlPath: String = "",
    val viewPath: String = "",
    /** Position-keyed; HA sections have no inherent stable id. */
    val sectionIndex: Int = 0,
    val title: String = "",
    val cards: List<CardSummary> = emptyList(),
    /** Stable id chosen at pin time. */
    val sectionKey: String = "",
    /** Position in the unified Wear top-level ordering. */
    val orderIndex: Int = 0,
)

/**
 * Mobile-assigned widget slot map. Wear declares 5 widget providers
 * (Slot0 … Slot4); each [WidgetSlot] points its slot at a pinned card
 * via [PinnedCard.cardKey]. An unassigned slot (`cardKey == ""`)
 * disables the corresponding provider on the wear side so it doesn't
 * appear in the system widget picker.
 */
@Serializable
data class WearWidgetSlots(
    val slots: List<WidgetSlot> = emptyList(),
    val updatedAtMs: Long = 0L,
)

@Serializable
data class WidgetSlot(
    /** 0..4. */
    val slotIndex: Int = 0,
    /** Empty when the slot is unconfigured. References [PinnedCard.cardKey]. */
    val cardKey: String = "",
    /**
     * Which Glance Wear container sizes this slot advertises in the
     * watch's widget picker. Defaults to 2 ([SlotSizePref.Both]).
     *
     *   0 = SMALL_ONLY
     *   1 = LARGE_ONLY
     *   2 = BOTH
     */
    val size: Int = 2,
)

/**
 * Logical mirror of [WidgetSlot.size]. Lives next to the wire model so
 * both modules can decode the int without depending on the mobile pin
 * store's enum.
 */
enum class SlotSizePref(val wireValue: Int) {
    SmallOnly(0),
    LargeOnly(1),
    Both(2);

    companion object {
        fun fromWire(wire: Int): SlotSizePref =
            entries.firstOrNull { it.wireValue == wire } ?: Both
    }
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
    const val SECTIONS: String = "/wear/sections"
    const val SLOTS: String = "/wear/slots"
    const val VALUES: String = "/wear/values"
    const val DASHBOARD_PREFIX: String = "/wear/dashboard/"
    const val LEASE_MESSAGE: String = "/wear/lease"
    const val STREAM_MESSAGE: String = "/wear/stream"

    /** Number of wear-widget slots. Mirrors [WearWidgetSlots] capacity. */
    const val WIDGET_SLOT_COUNT: Int = 5

    /** Encode a urlPath (which may be null for HA's default dashboard) into a DataItem path. */
    fun dashboardPath(urlPath: String?): String =
        DASHBOARD_PREFIX + (urlPath?.takeIf { it.isNotEmpty() }?.replace('/', '_') ?: "_default")

    /**
     * Lease window before phone falls back to batched DataStore writes.
     * Tuned for 30 s of wall-clock; wear sends a heartbeat every 10 s.
     */
    const val LEASE_WINDOW_MS: Long = 30_000L
}
