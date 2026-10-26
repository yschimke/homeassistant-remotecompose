package ee.schimke.terrazzo.wear.widget

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.glance.wear.GlanceWearWidgetManager
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import ee.schimke.terrazzo.wear.sync.WearOfflineStore
import ee.schimke.terrazzo.wear.sync.WearWidgetsController
import ee.schimke.terrazzo.wearsync.proto.WearPlacedWidget
import ee.schimke.terrazzo.wearsync.proto.WearPlacedWidgets
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import ee.schimke.terrazzo.wearsync.proto.WearWidgetCards
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import ee.schimke.terrazzo.wearsync.proto.protoPayload
import ee.schimke.terrazzo.wearsync.proto.putProtoPayload

/**
 * The phone chooses each placed widget's card. The watch tells it what is placed
 * ([publishPlacedWidgets], a [WearPlacedWidgets] at [WearSyncPaths.PLACED_WIDGETS]) and takes the
 * phone's answer ([WearWidgetCards] at [WearSyncPaths.WIDGET_CARDS]) into [WearWidgetCardStore]
 * ([applyWidgetCards]), where the widgets read it.
 */

/** A Terrazzo widget placed on the watch: its instance id, provider and size. */
data class PlacedWidget(val instanceId: Int, val provider: ComponentName, val large: Boolean)

/**
 * The Terrazzo widgets placed on the watch, or null when the system can't say (an older Wear OS, no
 * widget host, or the query failed): an unknown answer is not an empty one, so nothing is forgotten
 * or reported on a failed query.
 */
suspend fun placedCardWidgetsOrNull(context: Context): List<PlacedWidget>? {
  if (!WearWidgetsController.supportsWearWidgets()) return null
  val small = CardSmallWidgetService::class.java.name
  val large = CardLargeWidgetService::class.java.name
  val active =
    runCatching { GlanceWearWidgetManager(context.applicationContext).fetchActiveWidgets() }
      .onFailure { Log.w(TAG, "fetchActiveWidgets failed", it) }
      .getOrNull() ?: return null
  return active
    .filter {
      it.provider.packageName == context.packageName && it.provider.className in setOf(small, large)
    }
    .map { PlacedWidget(it.instanceId.id, it.provider, large = it.provider.className == large) }
    .distinctBy { it.instanceId }
    .sortedBy { it.instanceId }
}

/**
 * Brings [WearWidgetCardStore] in line with the widgets actually placed — a widget removed without
 * [CardWidget.onRemoved] running loses its card — and tells the phone what is placed. Returns the
 * placed widgets, or null when the system couldn't list them, in which case nothing changes.
 */
suspend fun publishPlacedWidgets(context: Context): List<PlacedWidget>? {
  val app = context.applicationContext
  val placed = placedCardWidgetsOrNull(app) ?: return null
  val store = WearWidgetCardStore(app)
  val dropped = store.retainOnly(placed.map { it.instanceId }.toSet())
  if (dropped > 0) Log.i(TAG, "forgot $dropped removed widget card(s)")
  for (widget in placed) defaultWidgetCard(app, store, widget.instanceId, widget.large)
  runCatching {
    val nodeName = Wearable.getNodeClient(app).localNode.await().displayName.orEmpty()
    val item =
      WearPlacedWidgets(
        widgets =
          placed.map {
            WearPlacedWidget(it.instanceId, it.large, store.read(it.instanceId)?.cardKey.orEmpty())
          },
        nodeName = nodeName,
        updatedAtMs = System.currentTimeMillis(),
      )
    val request =
      com.google.android.gms.wearable.PutDataMapRequest.create(WearSyncPaths.PLACED_WIDGETS)
        .apply { dataMap.putProtoPayload(encodeProto(item)) }
        .asPutDataRequest()
        .setUrgent()
    Wearable.getDataClient(app).putDataItem(request).await()
  }
    .onFailure {
      if (it is kotlinx.coroutines.CancellationException) throw it
      Log.w(TAG, "publishing placed widgets failed", it)
    }
  return placed
}

/**
 * The card widget [instanceId] shows, giving it the first card it can show when it has none: a new
 * widget draws a card at once, and the phone adopts it as its choice when it hears of the widget
 * (the same default the phone picks). Null when the phone has sent no card it can show.
 */
internal fun defaultWidgetCard(
  context: Context,
  store: WearWidgetCardStore,
  instanceId: Int,
  large: Boolean,
): WearWidgetCard? {
  store.read(instanceId)?.let {
    return it
  }
  val offline = WearOfflineStore(context.applicationContext)
  val option =
    widgetCardOptions(offline.readPinned(), offline.readSections()).forWidget(large).firstOrNull()
      ?: return null
  return WearWidgetCard(option.cardKey, option.card, System.currentTimeMillis()).also {
    store.write(instanceId, it)
  }
}

/**
 * Takes the phone's choices for this watch ([localNodeId]) from [cards] into [store]: each widget
 * it names shows that card, with a copy kept so it still draws if the phone later stops sending it.
 * True when any choice changed. Widgets it doesn't name keep what they have: the phone names every
 * widget it has heard of, and one it hasn't yet is answered on its next write.
 */
internal fun applyWidgetCards(
  store: WearWidgetCardStore,
  localNodeId: String,
  cards: WearWidgetCards,
): Boolean {
  var changed = false
  for (choice in cards.cards) {
    if (choice.nodeId != localNodeId) continue
    val current = store.read(choice.instanceId)
    if (current?.cardKey == choice.cardKey && current.card == choice.card) continue
    store.write(choice.instanceId, WearWidgetCard(choice.cardKey, choice.card, cards.updatedAtMs))
    changed = true
  }
  return changed
}

/**
 * Reads the phone's [WearWidgetCards] from the Data Layer into [WearWidgetCardStore]: the widgets
 * call this before they render, since the app may not have been running when the phone wrote it.
 * True when any choice changed.
 */
internal suspend fun syncWidgetCardsFromDataLayer(context: Context): Boolean {
  val app = context.applicationContext
  val client = Wearable.getDataClient(app)
  val uri =
    android.net.Uri.Builder()
      .scheme(PutDataRequest.WEAR_URI_SCHEME)
      .path(WearSyncPaths.WIDGET_CARDS)
      .build()
  val nodeId = runCatching { Wearable.getNodeClient(app).localNode.await().id }.getOrNull()
  val buffer = runCatching { client.getDataItems(uri).await() }.getOrNull()
  if (nodeId == null || buffer == null) {
    buffer?.release()
    return false
  }
  val newest =
    try {
      buffer
        .mapNotNull { item ->
          DataMapItem.fromDataItem(item).dataMap.protoPayload().read(client)?.let {
            decodeProto<WearWidgetCards>(it)
          }
        }
        .maxByOrNull { it.updatedAtMs }
    } finally {
      buffer.release()
    }
  return newest != null && applyWidgetCards(WearWidgetCardStore(app), nodeId, newest)
}

private const val TAG = "WearWidgets"

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
  kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value -> cont.resumeWith(Result.success(value)) }
    addOnFailureListener { error -> cont.resumeWith(Result.failure(error)) }
  }
