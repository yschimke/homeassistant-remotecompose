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
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.LauncherDesigns
import ee.schimke.ha.rc.LauncherLook
import ee.schimke.ha.rc.captureLauncherWidgetDocument
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.decodeHaAction
import ee.schimke.ha.rc.encodeHaAction
import ee.schimke.ha.rc.platformWidgetDocument
import ee.schimke.ha.rc.systemThemedWidgetsProfile
import ee.schimke.ha.rc.widgetsProfile
import ee.schimke.terrazzo.core.prefs.LauncherColoursPref
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.dashboard.CardDesignOverrideStore
import ee.schimke.terrazzo.terrazzoGraph
import ee.schimke.terrazzo.ui.toThemeStyle
import ee.schimke.terrazzo.ui.toUberTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-card home-screen widget. Extends [AppWidgetProvider] directly rather than RemoteCompose's
 * `RemoteComposeWidget` scaffolding so we can pin the capture to [widgetsProfile] — the launcher's
 * RemoteCompose runtime supports a stricter op set than the embedded AndroidX player, and
 * `RemoteComposeWidget` hard-codes `RcPlatformProfiles.ANDROIDX` inside its `RCWidget` capture.
 *
 * Flow:
 * 1. Framework or our own broadcast triggers [onUpdate].
 * 2. For each pinned widget id, look up the [WidgetStore.Entry] and headlessly capture the card via
 *    [captureLauncherWidgetDocument] with `profile = widgetsProfile`. The composition is wrapped
 *    with [ProvideCardRegistry] + [ProvideSystemHaTheme] so API 37+ launchers resolve Android's
 *    system colors at playback; older hosts receive the matching concrete light/dark fallback.
 * 3. Wrap the bytes in `RemoteViews.DrawInstructions` and publish via
 *    `AppWidgetManager.updateAppWidget(widgetId, …)`.
 *
 * Widgets pinned while the app was in demo mode carry the demo baseUrl marker; render those against
 * the current [DemoData] snapshot so values are non-empty. Live-mode widgets use the empty default
 * until a future background worker writes a fresh snapshot.
 *
 * API floor: Android 16 / API 36 — its platform player supports layout documents in
 * `RemoteViews.DrawInstructions`. The app also runs on API 35; providers are disabled there because
 * the original player lacks layouts. The `@RequiresApi` is here to keep lint quiet.
 */
open class TerrazzoWidgetProvider : AppWidgetProvider() {
  override fun onReceive(context: Context, intent: Intent) {
    if (
      Build.VERSION.SDK_INT < WidgetInstaller.MIN_HOST_API || intent.action !in asyncWidgetActions
    ) {
      super.onReceive(context, intent)
      return
    }
    val result = goAsync()
    widgetUpdateScope.launch {
      try {
        widgetUpdateMutex.withLock { super.onReceive(context, intent) }
      } catch (failure: Exception) {
        Log.e(TAG, "Widget broadcast failed: ${intent.action}", failure)
      } finally {
        result.finish()
      }
    }
  }

  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    super.onUpdate(context, appWidgetManager, appWidgetIds)
    if (Build.VERSION.SDK_INT < WidgetInstaller.MIN_HOST_API) return
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
    if (Build.VERSION.SDK_INT < WidgetInstaller.MIN_HOST_API) return
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
    // Live instances read the snapshot the app's live session keeps on disk (every entity, not
    // just one dashboard's), refreshed while a session is bound.
    val snapshot =
      if (DemoData.isDemo(entry.baseUrl)) DemoData.snapshot()
      else context.terrazzoGraph().offlineCache.latestSnapshot(entry.baseUrl) ?: EMPTY_SNAPSHOT

    val targetSizeDp =
      WidgetSizing.forWidgetCapture(
        appWidgetManager = appWidgetManager,
        widgetId = widgetId,
      )
    val remoteViews =
      renderPreview(context, entry.baseUrl, entry.card, snapshot, targetSizeDp, widgetId) ?: return
    appWidgetManager.updateAppWidget(widgetId, remoteViews)
  }

  /**
   * How [card]'s widget is drawn: in its own card style when the user picked one for it (a card
   * design override), else their card style; on the launcher's colours unless they chose the card
   * style's own ([LauncherColoursPref]).
   */
  private fun launcherLook(context: Context, card: ee.schimke.ha.model.CardConfig): LauncherLook =
    runBlocking {
      val prefs = context.terrazzoGraph().preferencesStore
      val override = CardDesignOverrideStore.of(context).overrides.value.forCard(card)?.cardStyle
      LauncherLook(
        cardStyle = override?.let(HaUberTheme::byId) ?: prefs.cardStyle.first().toUberTheme(),
        appPalette =
          if (prefs.launcherColours.first() == LauncherColoursPref.App)
            prefs.themeStyle.first().toThemeStyle()
          else null,
      )
    }

  /** The install preview and the installed widget share the exact same capture and theme. */
  internal fun renderPreview(
    context: Context,
    baseUrl: String,
    card: ee.schimke.ha.model.CardConfig,
    snapshot: HaSnapshot,
    targetSizeDp: WidgetSizeDp,
    widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
  ): RemoteViews? {
    val registry = defaultRegistry().withEnhancedShutter()
    val widthPx = WidgetSizing.dpToPx(context, targetSizeDp.widthDp)
    val heightPx = WidgetSizing.dpToPx(context, targetSizeDp.heightDp)
    val densityDpi = context.resources.configuration.densityDpi
    val captureProfile =
      if (Build.VERSION.SDK_INT >= SYSTEM_THEME_MIN_HOST_API) {
        systemThemedWidgetsProfile
      } else {
        widgetsProfile
      }

    val dark =
      context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    val look = launcherLook(context, card)

    // Share fetches (including failures) between the template and direct paths. Keep image bytes
    // comfortably below Binder's transaction limit even for cards containing several pictures.
    val images = mutableMapOf<String, ByteArray?>()
    var remainingImageBytes = 512 * 1024
    val fetchImage: (String) -> ByteArray? = { url ->
      if (url in images) images[url]
      else {
        runBlocking {
          WidgetImages.png(
            context,
            baseUrl,
            url,
            WIDGET_IMAGE_MAX_PX,
            maxBytes = minOf(256 * 1024, remainingImageBytes),
            fresh = true,
          )
        }
          ?.also { remainingImageBytes -= it.size }
          .also { images[url] = it }
      }
    }

    // Design first: the card's launcher template with its values baked in. Cards with no design,
    // or one that can't be baked, capture directly.
    val baked = runCatching {
      LauncherDesigns.render(
        context = context,
        card = card,
        snapshot = snapshot,
        widthDp = targetSizeDp.widthDp,
        heightDp = targetSizeDp.heightDp,
        densityDpi = densityDpi,
        dark = dark,
        systemTheme = captureProfile === systemThemedWidgetsProfile,
        registry = registry,
        fetchImage = fetchImage,
        look = look,
      )
    }
      .onFailure { Log.w(TAG, "launcher design failed for id=$widgetId", it) }
      .getOrNull()
    val remoteViews =
      if (baked != null) {
        RemoteViews(
            RemoteViews.DrawInstructions.Builder(listOf(platformWidgetDocument(baked.bytes)))
              .build()
          )
          .also { views ->
            baked.actions
              .filterKeys { widgetId != AppWidgetManager.INVALID_APPWIDGET_ID }
              .forEach { (actionId, action) ->
                val payload = encodeHaAction(action)
                views.setOnClickPendingIntent(
                  actionId,
                  appPendingIntent(context, widgetId, actionId, action)
                    ?: widgetActionPendingIntent(
                      context,
                      widgetId,
                      actionId,
                      payload,
                      resolved = true,
                    ),
                )
              }
          }
      } else {
        captureDirect(
          context,
          widgetId,
          card,
          snapshot,
          registry,
          widthPx,
          heightPx,
          densityDpi,
          captureProfile,
          dark,
          look,
          fetchImage,
        ) ?: return null
      }
    return remoteViews
  }

  @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
  private fun captureDirect(
    context: Context,
    widgetId: Int,
    card: ee.schimke.ha.model.CardConfig,
    snapshot: HaSnapshot,
    registry: ee.schimke.ha.rc.CardRegistry,
    widthPx: Int,
    heightPx: Int,
    densityDpi: Int,
    captureProfile: androidx.compose.remote.creation.profile.Profile,
    dark: Boolean,
    look: LauncherLook,
    fetchImage: (String) -> ByteArray?,
  ): RemoteViews? {
    val captured = runCatching {
      captureLauncherWidgetDocument(
        context,
        card,
        snapshot,
        registry,
        widthPx,
        heightPx,
        densityDpi,
        captureProfile,
        dark,
        look,
        fetchImage,
      )
    }
      .getOrElse {
        // The widgets profile rejects ops outside the launcher's vocabulary —
        // log and skip rather than crashing the host process.
        Log.w(TAG, "widgets-profile capture failed for id=$widgetId type=${card.type}", it)
        return null
      }
    val remoteViews =
      RemoteViews(
        RemoteViews.DrawInstructions.Builder(listOf(platformWidgetDocument(captured.bytes))).build()
      )
    captured.actions
      .filterKeys { widgetId != AppWidgetManager.INVALID_APPWIDGET_ID }
      .forEach { (actionId, payload) ->
        // The RC document emits HostActionMetadata(actionId, payload). Android's launcher-widget
        // bridge adds that payload to the PendingIntent's fill-in Intent as
        // "remotecompose_metadata". Keep a static copy too: it lets the receiver log whether the
        // platform forwarded metadata and remains a fallback on platform builds that omit it.
        remoteViews.setOnClickPendingIntent(
          actionId,
          appPendingIntent(context, widgetId, actionId, decodeHaAction(payload))
            ?: widgetActionPendingIntent(context, widgetId, actionId, payload),
        )
      }
    return remoteViews
  }

  /**
   * An activity [PendingIntent] for a tap that needs the app (or a browser) in front: the surface's
   * open-app tap ([ee.schimke.ha.rc.components.WidgetOpenApp]), and the card's more-info and
   * navigate taps, open the app; a URL tap opens it. Null for an action the receiver handles. A
   * receiver can't start an activity from the background, so these must be activity intents of
   * their own.
   */
  private fun appPendingIntent(
    context: Context,
    widgetId: Int,
    actionId: Int,
    action: HaAction?,
  ): PendingIntent? {
    val intent =
      when (action) {
        is HaAction.Url -> Intent(Intent.ACTION_VIEW, Uri.parse(action.url))
        is HaAction.MoreInfo,
        is HaAction.Navigate ->
          context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        else -> return null
      }.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Identity only (see widgetActionPendingIntent); a launch intent keeps its own extras.
        identifier = "terrazzo://widget-open/$widgetId/$actionId"
      }
    return PendingIntent.getActivity(
      context,
      actionId,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun widgetActionPendingIntent(
    context: Context,
    widgetId: Int,
    actionId: Int,
    payload: String,
    resolved: Boolean = false,
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
        if (resolved) putExtra(WidgetActionReceiver.EXTRA_RESOLVED_ACTION, payload)
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
    private val widgetUpdateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val widgetUpdateMutex = Mutex()
    private val asyncWidgetActions =
      setOf(
        AppWidgetManager.ACTION_APPWIDGET_UPDATE,
        AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED,
        AppWidgetManager.ACTION_APPWIDGET_DELETED,
      )

    // ColorTheme (wire op 196) is not understood by Android 16's platform
    // Remote Compose player. API 37 adds the host-side implementation.
    const val SYSTEM_THEME_MIN_HOST_API = 37
    val EMPTY_SNAPSHOT = HaSnapshot()
    const val TAG = "TerrazzoWidgetProvider"

    /** Matches the picture slot templates capture (`PictureImageStrategy.DefaultAppUrl`). */
    const val WIDGET_IMAGE_MAX_PX = 512
  }
}
