package ee.schimke.terrazzo.wearsync

import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths

/**
 * A set of Wearable Data Layer items to delete: the item at [path], or with [prefix] every item
 * whose path starts with it.
 */
internal data class WearDataItems(val path: String, val prefix: Boolean)

/**
 * Everything the phone writes for the signed-in account, deleted from the Data Layer on sign-out
 * ([MobileWearSyncManager.clearWatchData]) so the watch, which forgets each item as its deletion
 * arrives, keeps no dashboards, entity values, pictures or pins of the account. `/wear/settings`
 * stays: it carries no account data, and its base URL is rewritten empty as the session ends.
 *
 * The watch's listener service isn't woken for values and pictures (they change every refresh): it
 * forgets them when the deletion of pins, sections or slots — only ever deleted here — wakes it.
 */
internal val WATCH_ACCOUNT_DATA: List<WearDataItems> =
  listOf(
    WearDataItems(WearSyncPaths.VALUES, prefix = false),
    WearDataItems(WearSyncPaths.PICTURE_PREFIX, prefix = true),
    WearDataItems(WearSyncPaths.DASHBOARD_PREFIX, prefix = true),
    WearDataItems(WearSyncPaths.PINNED, prefix = false),
    WearDataItems(WearSyncPaths.SECTIONS, prefix = false),
    WearDataItems(WearSyncPaths.SLOTS, prefix = false),
    // A slot's Wear design may be the person's private one.
    WearDataItems(WearSyncPaths.SLOT_DESIGNS, prefix = false),
    WearDataItems(WearSyncPaths.DESIGN_PREFIX, prefix = true),
  )

/**
 * The dashboard items in the Data Layer ([existing] paths) that are no longer among the session's
 * dashboards ([current] paths, `WearSyncPaths.dashboardPath` each): a dashboard removed in Home
 * Assistant would otherwise stay on the watch for good. Only dashboard items are considered.
 */
internal fun staleDashboardPaths(
  existing: Collection<String>,
  current: Collection<String>,
): Set<String> =
  existing.filter { it.startsWith(WearSyncPaths.DASHBOARD_PREFIX) }.toSet() - current.toSet()

/**
 * The time to stamp values sent to the watch with: when the snapshot behind them was fetched
 * ([fetchedAtMs], from `HaSession.snapshotFetchedAtMs`) — a cached snapshot served while the phone
 * is backgrounded or offline is as old as its fetch, not as new as the send — else [nowMs] for a
 * snapshot fetched just now. Never later than [nowMs].
 */
internal fun valuesCapturedAtMs(fetchedAtMs: Long?, nowMs: Long): Long =
  fetchedAtMs?.coerceAtMost(nowMs) ?: nowMs

/** The entities in [current] that differ from what the watch was last sent ([baseline]). */
internal fun streamDeltas(
  baseline: Map<String, EntityValue>,
  current: Map<String, EntityValue>,
): Map<String, EntityValue> = current.filter { (k, v) -> baseline[k] != v }

/**
 * What the watch holds after a stream send: [sent] once it reached at least one watch, else still
 * [baseline] — so deltas lost to a failed send, or sent while no watch was connected, go out again
 * on the next tick instead of never.
 */
internal fun streamBaselineAfterSend(
  baseline: Map<String, EntityValue>,
  sent: Map<String, EntityValue>,
  delivered: Boolean,
): Map<String, EntityValue> = if (delivered) sent else baseline
