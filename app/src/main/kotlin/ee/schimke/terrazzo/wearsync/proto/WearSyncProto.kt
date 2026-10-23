package ee.schimke.terrazzo.wearsync.proto

import kotlinx.serialization.Serializable

/**
 * Wear ↔ phone sync schema. The same file is duplicated in the `app` module — both copies must stay
 * byte-for-byte identical so DataItem payloads and MessageClient frames decode on the peer.
 *
 * The values are encoded with `kotlinx.serialization.protobuf.ProtoBuf`, which produces standard
 * proto3 wire bytes — i.e. the same bytes that `protoc`/Wire would write. We choose this approach
 * (rather than generated classes via Wire's Gradle plugin) so the build stays self-contained on AGP
 * 9 / Gradle 9.3, which Wire's plugin doesn't yet recognise as a Kotlin host. The `.proto` file in
 * `src/main/proto/` documents the schema and stays the source of truth.
 *
 * Path conventions (mirrored in [WearSyncPaths]):
 * - DataClient:
 *     - `/wear/settings` — [WearSettings]
 *     - `/wear/pinned` — [PinnedCardSet]
 *     - `/wear/sections` — [PinnedSectionSet]
 *     - `/wear/slots` — [WearWidgetSlots] (no longer written or read; kept for wire compatibility)
 *     - `/wear/values` — [LiveValues]
 *     - `/wear/dashboard/<urlPath>` — [DashboardData]
 *     - `/wear/picture/<entityId>` — [WearPicture]
 *     - `/wear/slot-designs` — [WearSlotDesigns]
 *     - `/wear/design/<sha256>` — [WearDesignBlob]
 * - MessageClient (ephemeral):
 *     - `/wear/lease` — [WearLease] (wear → phone heartbeat)
 *     - `/wear/stream` — [StreamUpdate] (phone → wear delta push)
 *     - `/wear/access/request` — [WearAccessRequest] (wear → phone: "I have no token")
 *     - `/wear/access` — [WearAccess] (phone → wear: the watch's own token)
 */

/** Wear-side settings written by the phone. Drives demo badge and routing. */
@Serializable
data class WearSettings(
  val demoMode: Boolean = false,
  val baseUrl: String = "",
  val updatedAtMs: Long = 0L,
  /** Explicit capability from the phone; absent on older phones means read-only. */
  val inputEditingEnabled: Boolean = false,
)

/** Card extract phone-side; wear renders a list row from this. */
@Serializable
data class CardSummary(
  val type: String = "",
  val title: String = "",
  val primaryEntityId: String = "",
  val rawJson: String = "",
  /** Explicit phone card/type style override; empty follows the watch style. */
  val cardStyleOverride: String = "",
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
  /** The entity's `entity_picture` URL; the picture itself travels as a [WearPicture]. */
  val entityPicture: String = "",
  /**
   * The decimals the phone's `formatState` rounds this state to — the entity registry's display
   * precision, else the entity's `display_precision` / `suggested_display_precision` — so the watch
   * formats it identically. Null for the formatter's device-class default.
   */
  val displayPrecision: Int? = null,
  /**
   * The few other attributes a watch render reads, as their string values: a Bambu spool's `color`,
   * `remain`, `remain_enabled` and `active` ([WEAR_EXTRA_ATTRIBUTES]). Only those, to keep the
   * DataItem small.
   */
  val attributes: Map<String, String> = emptyMap(),
)

/** The attributes beyond the named fields that travel in [EntityValue.attributes]. */
val WEAR_EXTRA_ATTRIBUTES: Set<String> = setOf("color", "remain", "remain_enabled", "active")

/**
 * An entity's picture for Wear widgets, which can't fetch one: fetched and downscaled on the phone,
 * PNG-encoded (the format a baked widget embeds). [url] is the `entity_picture` it was fetched
 * from.
 */
@Serializable
class WearPicture(
  val entityId: String = "",
  val url: String = "",
  val png: ByteArray = ByteArray(0),
  val updatedAtMs: Long = 0L,
)

/**
 * The Wear widget design the phone chose for each pinned card, per container: a design server
 * design made from the card's own design for that container (`small` or `large`). The template
 * travels as a [WearDesignBlob]; a card or container without one draws the card's own design. (The
 * path and name still say "slot": older phones sent designs for their widget slot cards only.)
 */
@Serializable
data class WearSlotDesigns(
  val designs: List<WearSlotDesign> = emptyList(),
  val updatedAtMs: Long = 0L,
)

@Serializable
data class WearSlotDesign(
  /** The card: a [PinnedCard.cardKey]. */
  val cardKey: String = "",
  /** `small` or `large`: the Glance Wear container the design fills. */
  val container: String = "",
  /** The [WearDesignBlob] holding its template. */
  val sha256: String = "",
)

/** A Wear widget design's Remote Compose template, content-addressed by [sha256]. */
@Serializable class WearDesignBlob(val sha256: String = "", val rc: ByteArray = ByteArray(0))

/** MessageClient stream frame — only entities that changed. */
@Serializable
data class StreamUpdate(val deltas: List<EntityDelta> = emptyList(), val capturedAtMs: Long = 0L)

@Serializable
data class EntityDelta(val entityId: String = "", val value: EntityValue = EntityValue())

/**
 * User-curated pinned cards. Top-level destinations on Wear, and (with the pinned sections' cards)
 * what the watch's widget picker offers; a placed widget remembers its card by
 * [PinnedCard.cardKey].
 */
@Serializable
data class PinnedCardSet(val cards: List<PinnedCard> = emptyList(), val updatedAtMs: Long = 0L)

@Serializable
data class PinnedCard(
  val baseUrl: String = "",
  val card: CardSummary = CardSummary(),
  /** Stable id chosen at pin time; what a watch widget and [WearSlotDesign.cardKey] name it by. */
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
 * Unused: kept for wire compatibility with older phone and watch builds. Phones no longer write
 * `/wear/slots` and watches ignore it; a watch widget's card is chosen on the watch.
 *
 * Was: mobile-assigned widget slot map. Wear declared 5 widget providers (Slot0 … Slot4); each
 * [WidgetSlot] points its slot at a pinned card via [PinnedCard.cardKey]. An unassigned slot
 * (`cardKey == ""`) disables the corresponding provider on the wear side so it doesn't appear in
 * the system widget picker.
 */
@Serializable
data class WearWidgetSlots(val slots: List<WidgetSlot> = emptyList(), val updatedAtMs: Long = 0L)

/** Unused: kept for wire compatibility, like [WearWidgetSlots]. */
@Serializable
data class WidgetSlot(
  /** 0..4. */
  val slotIndex: Int = 0,
  /** Empty when the slot is unconfigured. References [PinnedCard.cardKey]. */
  val cardKey: String = "",
  /**
   * Which Glance Wear container sizes this slot advertises in the watch's widget picker. Defaults
   * to 2 ([SlotSizePref.Both]).
   *
   * 0 = SMALL_ONLY 1 = LARGE_ONLY 2 = BOTH
   */
  val size: Int = 2,
)

/**
 * Unused: kept for wire compatibility, like [WearWidgetSlots].
 *
 * Logical mirror of [WidgetSlot.size]. Lives next to the wire model so both modules can decode the
 * int without depending on the mobile pin store's enum.
 */
enum class SlotSizePref(val wireValue: Int) {
  SmallOnly(0),
  LargeOnly(1),
  Both(2);

  companion object {
    fun fromWire(wire: Int): SlotSizePref = entries.firstOrNull { it.wireValue == wire } ?: Both
  }
}

/**
 * Heartbeat sent from wear → phone over MessageClient. Phone treats the arrival time as a lease
 * window; while a recent lease is active phone pushes [StreamUpdate]s, otherwise it batches into
 * [LiveValues].
 */
@Serializable data class WearLease(val sentAtMs: Long = 0L, val foreground: Boolean = false)

/**
 * Wear → phone over MessageClient: the watch has no Home Assistant token of its own (or Home
 * Assistant refused it) and asks the phone for one.
 */
@Serializable data class WearAccessRequest(val sentAtMs: Long = 0L)

/**
 * Phone → wear over MessageClient, never a DataItem, so it isn't kept in the Data Layer: a
 * long-lived access token the phone made for this watch alone, named for it in the user's Home
 * Assistant profile, so the watch can read its cards' values itself when the phone has none to
 * send. [baseUrl] is the instance the phone is signed in to; [externalUrl] is Home Assistant's
 * `external_url` (remote access), empty when it has none or it is [baseUrl].
 */
@Serializable
class WearAccess(
  val baseUrl: String = "",
  val externalUrl: String = "",
  val token: String = "",
  val issuedAtMs: Long = 0L,
)

/**
 * Phone-local diagnostics. Stored at `terrazzo_sync_stats.pb` and surfaced in Settings →
 * Diagnostics.
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
  /** [WearWidgetSlots]: no longer written or read, kept so both sides name the path alike. */
  const val SLOTS: String = "/wear/slots"
  const val VALUES: String = "/wear/values"
  const val DASHBOARD_PREFIX: String = "/wear/dashboard/"
  const val PICTURE_PREFIX: String = "/wear/picture/"
  const val SLOT_DESIGNS: String = "/wear/slot-designs"
  const val DESIGN_PREFIX: String = "/wear/design/"
  const val LEASE_MESSAGE: String = "/wear/lease"
  const val STREAM_MESSAGE: String = "/wear/stream"
  const val ACCESS_REQUEST_MESSAGE: String = "/wear/access/request"
  const val ACCESS_MESSAGE: String = "/wear/access"

  /**
   * A tap on a watch card: `{id, card, action}`, the card's `rawJson` as the phone sent it and the
   * action (an encoded `HaAction`) the tap resolved to. The phone runs it only when that card makes
   * it, and answers `{id, ok, error?}` on [TAP_RESULT_MESSAGE].
   */
  const val TAP_MESSAGE: String = "/wear/tap/v1"
  const val TAP_RESULT_MESSAGE: String = "/wear/tap-result/v1"

  /** The DataItem path of [entityId]'s [WearPicture]. */
  fun picturePath(entityId: String): String = PICTURE_PREFIX + entityId

  /** The DataItem path of the [WearDesignBlob] whose template hashes to [sha256]. */
  fun designPath(sha256: String): String = DESIGN_PREFIX + sha256

  /** Number of wear-widget slots in [WearWidgetSlots]. Unused: kept for wire compatibility. */
  const val WIDGET_SLOT_COUNT: Int = 5

  /** Encode a urlPath (which may be null for HA's default dashboard) into a DataItem path. */
  fun dashboardPath(urlPath: String?): String =
    DASHBOARD_PREFIX + (urlPath?.takeIf { it.isNotEmpty() }?.replace('/', '_') ?: "_default")

  /**
   * Lease window before phone falls back to batched DataStore writes. Tuned for 30 s of wall-clock;
   * wear sends a heartbeat every 10 s.
   */
  const val LEASE_WINDOW_MS: Long = 30_000L
}
