package ee.schimke.terrazzo.notify

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.LauncherDirectCapture
import ee.schimke.ha.rc.LauncherLook
import ee.schimke.ha.rc.captureLauncherWidgetDocument
import ee.schimke.ha.rc.cardHeightDp
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.decodeHaAction
import ee.schimke.ha.rc.platformWidgetDocument
import ee.schimke.ha.rc.widgetsProfile
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.widget.WidgetInstaller
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A live Remote Compose card for a notification's entity (`data.entity_id`), as the expanded
 * notification body: the entity's tile, captured exactly as a launcher widget is
 * ([captureLauncherWidgetDocument]) and played by the platform's RemoteViews player.
 *
 * Its buttons work as a widget's do. Each control in the document is a host action: an action id
 * plus the encoded [ee.schimke.ha.rc.components.HaAction]. [RemoteViews.setOnClickPendingIntent]
 * binds each id to a PendingIntent, and a tap fires it with the player's metadata for the action
 * (`remotecompose_metadata`). Here the PendingIntent goes to [HaNotificationActionReceiver], which
 * runs a toggle or service call on Home Assistant (the app's socket while connected, else a
 * short-lived session) and opens the app for a details or navigate tap.
 *
 * API 36+ only, where the platform player supports layout documents (as for widgets); older devices
 * get the plain notification.
 */
object NotificationCards {
  private const val TAG = "NotificationCards"
  /** Android caps a custom expanded notification body at 256dp. */
  private const val MAX_HEIGHT_DP = 256
  /** The notification's content inset, each side, approximately. */
  private const val SIDE_INSET_DP = 32

  suspend fun render(context: Context, session: HaSession, entityId: String): RemoteViews? {
    if (Build.VERSION.SDK_INT < WidgetInstaller.MIN_HOST_API) return null
    val snapshot =
      session.liveStates.value ?: runCatching { session.loadSnapshot() }.getOrNull() ?: return null
    if (entityId !in snapshot.states) return null
    return runCatching { capture(context, session.baseUrl, entityId, snapshot) }
      .onFailure { Log.w(TAG, "Couldn't draw a card for $entityId", it) }
      .getOrNull()
  }

  internal suspend fun capture(
    context: Context,
    baseUrl: String,
    entityId: String,
    snapshot: HaSnapshot,
  ): RemoteViews {
    val captured = captureDocument(context, entityId, snapshot)
    val views =
      RemoteViews(
        RemoteViews.DrawInstructions.Builder(listOf(platformWidgetDocument(captured.bytes))).build()
      )
    boundActions(captured).forEach { (actionId, action) ->
      HaNotificationActionReceiver.cardAction(context, baseUrl, entityId, actionId, action)?.let {
        views.setOnClickPendingIntent(actionId, it)
      }
    }
    return views
  }

  /** The entity's tile as a platform document, with its host actions. */
  internal suspend fun captureDocument(
    context: Context,
    entityId: String,
    snapshot: HaSnapshot,
  ): LauncherDirectCapture =
    withContext(Dispatchers.Default) {
      val card = tileFor(entityId)
      val registry = defaultRegistry()
      val metrics = context.resources.displayMetrics
      val density = metrics.density
      val widthPx = (metrics.widthPixels - 2 * SIDE_INSET_DP * density).roundToInt()
      val heightDp = registry.cardHeightDp(card, snapshot).coerceAtMost(MAX_HEIGHT_DP)
      val dark =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
          Configuration.UI_MODE_NIGHT_YES
      captureLauncherWidgetDocument(
        context = context,
        card = card,
        snapshot = snapshot,
        registry = registry,
        widthPx = widthPx,
        heightPx = (heightDp * density).roundToInt(),
        densityDpi = metrics.densityDpi,
        captureProfile = widgetsProfile,
        dark = dark,
        look = LauncherLook(),
        fetchImage = { null },
      )
    }

  /** [captured]'s host actions, decoded: the buttons a notification can bind. */
  internal fun boundActions(captured: LauncherDirectCapture): Map<Int, HaAction> =
    captured.actions
      .mapNotNull { (id, payload) -> decodeHaAction(payload)?.let { id to it } }
      .toMap()

  internal fun tileFor(entityId: String) =
    CardConfig(
      "tile",
      buildJsonObject {
        put("type", JsonPrimitive("tile"))
        put("entity", JsonPrimitive(entityId))
      },
    )
}
