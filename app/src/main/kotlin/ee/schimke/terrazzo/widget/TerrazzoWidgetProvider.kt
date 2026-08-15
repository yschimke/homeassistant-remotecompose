@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.ProvideCardSizeMode
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.cardHeightDp
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideCardChrome
import ee.schimke.ha.rc.components.ProvideSystemHaTheme
import ee.schimke.ha.rc.components.ProvideWidgetActionRegistry
import ee.schimke.ha.rc.components.RemoteHaWidgetSurface
import ee.schimke.ha.rc.components.WidgetActionRegistry
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.systemThemedWidgetsProfile
import ee.schimke.ha.rc.widgetsProfile
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.terrazzoGraph
import kotlinx.coroutines.runBlocking

/**
 * Per-card home-screen widget. Extends [AppWidgetProvider] directly rather than RemoteCompose's
 * `RemoteComposeWidget` scaffolding so we can pin the capture to [widgetsProfile] — the launcher's
 * RemoteCompose runtime supports a stricter op set than the embedded AndroidX player, and
 * `RemoteComposeWidget` hard-codes `RcPlatformProfiles.ANDROIDX` inside its `RCWidget` capture.
 *
 * Flow:
 * 1. Framework or our own broadcast triggers [onUpdate].
 * 2. For each pinned widget id, look up the [WidgetStore.Entry] and headlessly capture the card via
 *    [captureSingleRemoteDocument] with `profile = widgetsProfile`. The composition is wrapped with
 *    [ProvideCardRegistry] + [ProvideSystemHaTheme] so API 37+ launchers resolve Android's system
 *    colors at playback; older hosts receive the matching concrete light/dark fallback.
 * 3. Wrap the bytes in `RemoteViews.DrawInstructions` and publish via
 *    `AppWidgetManager.updateAppWidget(widgetId, …)`.
 *
 * Widgets pinned while the app was in demo mode carry the demo baseUrl marker; render those against
 * the current [DemoData] snapshot so values are non-empty. Live-mode widgets use the empty default
 * until a future background worker writes a fresh snapshot.
 *
 * API floor: VANILLA_ICE_CREAM (Android 15 / API 35) — needed for `RemoteViews.DrawInstructions`.
 * The app's minSdk of 36 already satisfies this; the `@RequiresApi` is here only to keep lint
 * quiet.
 */
open class TerrazzoWidgetProvider : AppWidgetProvider() {

  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    super.onUpdate(context, appWidgetManager, appWidgetIds)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    for (id in appWidgetIds) {
      renderAndPublish(context, appWidgetManager, id)
    }
  }

  /**
   * The launcher calls this when the user resizes the widget (the cell span changed). Re-capture
   * the card at the new slot size and republish: the `.rc` document bakes its canvas size at
   * capture time, so without a fresh capture the launcher keeps painting the old document — which,
   * replayed into the new (larger or smaller) cell, leaves the surface short of the slot edges or,
   * on a re-used player, blank. Re-rendering authors the document at the new size so the surface
   * fills the cell and the card's size-breakpoints reflow to it.
   */
  override fun onAppWidgetOptionsChanged(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
    newOptions: android.os.Bundle,
  ) {
    super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    renderAndPublish(context, appWidgetManager, appWidgetId)
  }

  /**
   * The launcher tells us these widget ids were removed from the home screen. Drop their persisted
   * rows so the install cap frees the slots back up — a stale row would otherwise keep burning one
   * of the five even though nothing renders for it anymore.
   */
  override fun onDeleted(context: Context, appWidgetIds: IntArray) {
    super.onDeleted(context, appWidgetIds)
    val store = context.terrazzoGraph().widgetStore
    runBlocking {
      for (id in appWidgetIds) {
        store.remove(id)
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
  private fun renderAndPublish(
    context: Context,
    appWidgetManager: AppWidgetManager,
    widgetId: Int,
  ) {
    val entry = runBlocking { context.terrazzoGraph().widgetStore.get(widgetId) }
    if (entry == null) {
      // Pin in flight, or the entry was evicted. The framework
      // will call onUpdate again once the install receiver writes
      // the row.
      return
    }
    val snapshot = if (DemoData.isDemo(entry.baseUrl)) DemoData.snapshot() else EMPTY_SNAPSHOT
    val registry = defaultRegistry().withEnhancedShutter()

    val targetSizeDp =
      WidgetSizing.forWidgetCapture(
        appWidgetManager = appWidgetManager,
        widgetId = widgetId,
        cardHeightDp = registry.cardHeightDp(entry.card, snapshot),
      )
    val widthPx = WidgetSizing.dpToPx(context, targetSizeDp.widthDp)
    val heightPx = WidgetSizing.dpToPx(context, targetSizeDp.heightDp)
    val densityDpi = context.resources.configuration.densityDpi
    val widgetActions =
      WidgetActionRegistry(snapshot.states.mapValues { (_, entity) -> formatState(entity) })
    val captureProfile =
      if (Build.VERSION.SDK_INT >= SYSTEM_THEME_MIN_HOST_API) {
        systemThemedWidgetsProfile
      } else {
        widgetsProfile
      }

    val captured =
      runCatching {
          runBlocking {
            captureSingleRemoteDocument(
              context = context,
              creationDisplayInfo = RemoteCreationDisplayInfo(widthPx, heightPx, densityDpi),
              profile = captureProfile,
            ) {
              ProvideCardRegistry(registry) {
                ProvideSystemHaTheme(
                  profile = captureProfile,
                  fallbackTheme =
                    if (
                      context.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                    ) {
                      HaTheme.Dark
                    } else {
                      HaTheme.Light
                    },
                ) {
                  ProvideCardSizeMode(CardSizeMode.Fixed) {
                    // The widget surface paints the themed
                    // card background across the whole
                    // capture canvas, so the launcher cell is
                    // fully covered even when the card's
                    // content is shorter than the slot.
                    // Suppress the inner card's own chrome so
                    // it doesn't draw a second frame inside.
                    ProvideCardChrome(enabled = false) {
                      ProvideWidgetActionRegistry(widgetActions) {
                        RemoteHaWidgetSurface {
                          RenderChild(entry.card, snapshot, RemoteModifier.fillMaxWidth())
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
        .getOrElse {
          // The widgets profile rejects ops outside the launcher's vocabulary —
          // log and skip rather than crashing the host process.
          Log.w(TAG, "widgets-profile capture failed for id=$widgetId type=${entry.card.type}", it)
          return
        }

    val instructions = RemoteViews.DrawInstructions.Builder(listOf(captured.bytes)).build()
    val remoteViews = RemoteViews(instructions)
    widgetActions.entries.forEach { (actionId, payload) ->
      // The RC document emits HostActionMetadata(actionId, payload). Android's launcher-widget
      // bridge adds that payload to the PendingIntent's fill-in Intent as
      // "remotecompose_metadata". Keep a static copy too: it lets the receiver log whether the
      // platform forwarded metadata and remains a fallback on platform builds that omit it.
      remoteViews.setOnClickPendingIntent(
        actionId,
        widgetActionPendingIntent(context, widgetId, actionId, payload),
      )
    }
    appWidgetManager.updateAppWidget(widgetId, remoteViews)
  }

  private fun widgetActionPendingIntent(
    context: Context,
    widgetId: Int,
    actionId: Int,
    payload: String,
  ): PendingIntent {
    val intent =
      Intent(context, WidgetActionReceiver::class.java).apply {
        action = WidgetActionReceiver.ACTION_WIDGET_ACTION
        // Intent extras don't participate in PendingIntent identity. A unique data URI prevents
        // one action's FLAG_UPDATE_CURRENT payload from replacing a sibling action on the card.
        data = Uri.parse("terrazzo://widget-action/$widgetId/$actionId")
        putExtra(WidgetActionReceiver.EXTRA_WIDGET_ID, widgetId)
        putExtra(WidgetActionReceiver.EXTRA_ACTION_ID, actionId)
        putExtra(WidgetActionReceiver.EXTRA_ACTION_PAYLOAD, payload)
      }
    return PendingIntent.getBroadcast(
      context,
      actionId,
      intent,
      // RemoteViews supplies RemoteCompose metadata through a fill-in Intent. Android ignores
      // fill-in fields for immutable PendingIntents, so this explicit, non-exported receiver token
      // must be mutable for the metadata experiment.
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
  }

  private companion object {
    // ColorTheme (wire op 196) is not understood by Android 16's platform
    // Remote Compose player. API 37 adds the host-side implementation.
    const val SYSTEM_THEME_MIN_HOST_API = 37
    val EMPTY_SNAPSHOT = HaSnapshot()
    const val TAG = "TerrazzoWidgetProvider"
  }
}

/**
 * Size-class provider variants. They share [TerrazzoWidgetProvider]'s id-driven rendering verbatim
 * — the only thing that differs is the `appwidget-provider` metadata declared against each in the
 * manifest (`targetCell*` default + `min/maxResize*` bounds), which is how a card's
 * [WidgetSizeClass][ee.schimke.terrazzo.widget.WidgetSizeClass] reaches the launcher's resize UI.
 * [WidgetInstaller] picks the matching component at pin time; refresh broadcasts can still target
 * the base provider since rendering only ever keys off the widget id.
 *
 * @see WidgetSizeClass
 */
class TerrazzoWidgetProviderSmall : TerrazzoWidgetProvider()

class TerrazzoWidgetProviderTall : TerrazzoWidgetProvider()
