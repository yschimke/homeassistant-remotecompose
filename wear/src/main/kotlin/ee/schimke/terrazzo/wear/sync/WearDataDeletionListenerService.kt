package ee.schimke.terrazzo.wear.sync

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import ee.schimke.terrazzo.wear.widget.refreshAllSlotWidgets
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import ee.schimke.terrazzo.wearsync.proto.WearWidgetSlots
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import kotlinx.coroutines.runBlocking

/**
 * Forgets what the phone deleted from the Data Layer while the watch app isn't running: on sign-out
 * the phone deletes the account's dashboards, values, pictures and pins, and a widget would
 * otherwise keep rendering them from [WearOfflineStore]. ([WearSyncRepository] does the same while
 * the app runs.)
 *
 * The manifest filter wakes this only for the rarely written items — dashboards, pins, sections,
 * slots, the slot cards' Wear design index — never for `/wear/values` or pictures, which change on
 * every refresh: see [forgottenOnDeletion]. A changed design index redraws the widgets
 * ([redrawsOnChange]), which read their new designs from the Data Layer.
 *
 * Changed items are kept too ([persistChanged]), so the app opens on what the phone last sent
 * rather than on an older copy it replaces a moment later; and new pins or sections warm their
 * cards ([WearCardWarmup]), so opening the app finds their templates ready.
 */
class WearDataDeletionListenerService : WearableListenerService() {

  /** The watch's own Home Assistant token, sent while the app may not be running. */
  override fun onMessageReceived(event: MessageEvent) {
    if (event.path == WearSyncPaths.ACCESS_MESSAGE) acceptAccess(applicationContext, event.data)
  }

  override fun onDataChanged(events: DataEventBuffer) {
    val deleted =
      events.filter { it.type == DataEvent.TYPE_DELETED }.mapNotNull { it.dataItem.uri.path }
    val changedItems =
      events
        .filter { it.type == DataEvent.TYPE_CHANGED }
        .mapNotNull { event ->
          val path = event.dataItem.uri.path ?: return@mapNotNull null
          path to
            DataMapItem.fromDataItem(event.dataItem)
              .dataMap
              .getByteArray(WearSyncRepository.KEY_PROTO)
        }
    val changed = changedItems.map { it.first }
    if (changedItems.isNotEmpty()) {
      val store = WearOfflineStore(applicationContext)
      for ((path, bytes) in changedItems) if (bytes != null) store.persistChanged(path, bytes)
      // Called on a background thread: capturing here costs the user nothing.
      if (warmsOnChange(changed)) {
        runBlocking { runCatching { WearCardWarmup.run(applicationContext) } }
      }
    }
    if (deleted.isEmpty()) {
      // New Wear designs for the slot cards: the widgets read them from the Data Layer as they
      // redraw.
      if (redrawsOnChange(changed)) {
        runBlocking { runCatching { refreshAllSlotWidgets(applicationContext) } }
      }
      return
    }
    // Signed out: the watch's own Home Assistant token goes too.
    if (signsOut(deleted)) WearAccessStore(applicationContext).clear()
    val store = WearOfflineStore(applicationContext)
    for (path in forgottenOnDeletion(deleted)) {
      if (path == WearSyncPaths.PICTURE_PREFIX) store.forgetAllPictures() else store.forget(path)
    }
    // Called on a background thread: the widgets redraw from what is left before returning.
    runBlocking { runCatching { refreshAllSlotWidgets(applicationContext) } }
  }
}

/**
 * What the watch forgets when the phone deletes the items at [deleted] paths: those, and — when one
 * is an account-wide item (pins, sections, slots), which the phone deletes only on sign-out — the
 * entity values, every picture ([WearSyncPaths.PICTURE_PREFIX] standing for all of them) and the
 * slot cards' Wear designs too, whose own deletions don't wake the listener service.
 */
internal fun forgottenOnDeletion(deleted: Collection<String>): List<String> {
  val signedOut = signsOut(deleted)
  val extra =
    if (signedOut) {
      listOf(WearSyncPaths.VALUES, WearSyncPaths.PICTURE_PREFIX, WearSyncPaths.SLOT_DESIGNS)
    } else emptyList()
  return (deleted + extra).distinct()
}

/**
 * Keeps the item the phone changed at [path] when it is one the app opens on and this service is
 * woken for: pins, sections, widget slots, dashboards. (The slot cards' design index is the
 * widgets' to read from the Data Layer.) [WearSyncRepository] does the same while the app runs.
 *
 * Only a newer copy than the one kept replaces it ([newerThan]): each node that ever wrote a path
 * (a replaced phone, say) keeps its own item, and their events can arrive in any order.
 */
internal fun WearOfflineStore.persistChanged(path: String, bytes: ByteArray) {
  when {
    path == WearSyncPaths.PINNED ->
      newerThan(decodeProto<PinnedCardSet>(bytes), readPinned(), PinnedCardSet::updatedAtMs)
        ?.let(::writePinned)
    path == WearSyncPaths.SECTIONS ->
      newerThan(
          decodeProto<PinnedSectionSet>(bytes),
          readSections(),
          PinnedSectionSet::updatedAtMs,
        )
        ?.let(::writeSections)
    path == WearSyncPaths.SLOTS ->
      newerThan(decodeProto<WearWidgetSlots>(bytes), readSlots(), WearWidgetSlots::updatedAtMs)
        ?.let(::writeSlots)
    path.startsWith(WearSyncPaths.DASHBOARD_PREFIX) ->
      decodeProto<DashboardData>(bytes)?.let { changed ->
        val kept = readAllDashboards().firstOrNull { it.urlPath == changed.urlPath }
        newerThan(changed, kept, DashboardData::updatedAtMs)?.let(::writeDashboard)
      }
  }
}

/** Whether the items changed at [changed] paths change the cards the app opens on. */
internal fun warmsOnChange(changed: Collection<String>): Boolean =
  WearSyncPaths.PINNED in changed || WearSyncPaths.SECTIONS in changed

/** Whether the items changed at [changed] paths need the placed widgets redrawn: a design index. */
internal fun redrawsOnChange(changed: Collection<String>): Boolean =
  WearSyncPaths.SLOT_DESIGNS in changed

/**
 * Whether the phone deleting the items at [deleted] paths means it signed out: it deletes the
 * account-wide items (pins, sections, slots) only then.
 */
internal fun signsOut(deleted: Collection<String>): Boolean = deleted.any { it in SIGN_OUT_MARKERS }

private val SIGN_OUT_MARKERS =
  setOf(WearSyncPaths.PINNED, WearSyncPaths.SECTIONS, WearSyncPaths.SLOTS)
