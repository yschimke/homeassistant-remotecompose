package ee.schimke.terrazzo.wear.sync

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import ee.schimke.terrazzo.wear.widget.refreshAllSlotWidgets
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import kotlinx.coroutines.runBlocking

/**
 * Forgets what the phone deleted from the Data Layer while the watch app isn't running: on sign-out
 * the phone deletes the account's dashboards, values, pictures and pins, and a widget would
 * otherwise keep rendering them from [WearOfflineStore]. ([WearSyncRepository] does the same while
 * the app runs.)
 *
 * The manifest filter wakes this only for the rarely written items — dashboards, pins, sections,
 * slots — never for `/wear/values` or pictures, which change on every refresh: see
 * [forgottenOnDeletion].
 */
class WearDataDeletionListenerService : WearableListenerService() {

  override fun onDataChanged(events: DataEventBuffer) {
    val deleted =
      events.filter { it.type == DataEvent.TYPE_DELETED }.mapNotNull { it.dataItem.uri.path }
    if (deleted.isEmpty()) return
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
  val signedOut = deleted.any { it in SIGN_OUT_MARKERS }
  val extra =
    if (signedOut) {
      listOf(WearSyncPaths.VALUES, WearSyncPaths.PICTURE_PREFIX, WearSyncPaths.SLOT_DESIGNS)
    } else emptyList()
  return (deleted + extra).distinct()
}

private val SIGN_OUT_MARKERS =
  setOf(WearSyncPaths.PINNED, WearSyncPaths.SECTIONS, WearSyncPaths.SLOTS)
