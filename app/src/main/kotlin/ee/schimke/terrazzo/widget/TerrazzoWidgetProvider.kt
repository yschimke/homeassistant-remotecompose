@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.cardHeightDp
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import ee.schimke.ha.rc.widgetsV6
import ee.schimke.terrazzo.core.prefs.DarkModePref
import ee.schimke.terrazzo.core.prefs.ThemePref
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.terrazzoGraph
import kotlinx.coroutines.runBlocking

/**
 * Per-card home-screen widget. Extends [AppWidgetProvider] directly
 * rather than RemoteCompose's `RemoteComposeWidget` scaffolding so we
 * can pin the capture to [widgetsV6] — the launcher's RemoteCompose
 * runtime supports a stricter op set than the embedded AndroidX
 * player, and `RemoteComposeWidget` hard-codes
 * `RcPlatformProfiles.ANDROIDX` inside its `RCWidget` capture.
 *
 * Flow:
 *   1. Framework or our own broadcast triggers [onUpdate].
 *   2. For each pinned widget id, look up the [WidgetStore.Entry] and
 *      headlessly capture the card via [captureSingleRemoteDocument]
 *      with `profile = widgetsV6`. The composition is wrapped with
 *      [ProvideCardRegistry] + [ProvideHaTheme] so converters resolve
 *      the user's theme.
 *   3. Wrap the bytes in `RemoteViews.DrawInstructions` and publish via
 *      `AppWidgetManager.updateAppWidget(widgetId, …)`.
 *
 * Widgets pinned while the app was in demo mode carry the demo
 * baseUrl marker; render those against the current [DemoData] snapshot
 * so values are non-empty. Live-mode widgets use the empty default
 * until a future background worker writes a fresh snapshot.
 *
 * API floor: VANILLA_ICE_CREAM (Android 15 / API 35) — needed for
 * `RemoteViews.DrawInstructions`. The app's minSdk of 36 already
 * satisfies this; the `@RequiresApi` is here only to keep lint quiet.
 */
class TerrazzoWidgetProvider : AppWidgetProvider() {

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
        val (style, dark) = loadThemeChoice(context)
        val haTheme = haThemeFor(style, dark)
        val registry = defaultRegistry()

        val widthPx = dpToPx(context, WIDGET_WIDTH_DP)
        val heightPx = dpToPx(context, registry.cardHeightDp(entry.card, snapshot))
        val densityDpi = context.resources.configuration.densityDpi

        val captured = runCatching {
            runBlocking {
                captureSingleRemoteDocument(
                    context = context,
                    creationDisplayInfo = RemoteCreationDisplayInfo(widthPx, heightPx, densityDpi),
                    profile = widgetsV6,
                ) {
                    ProvideCardRegistry(registry) {
                        ProvideHaTheme(haTheme) {
                            RenderChild(entry.card, snapshot, RemoteModifier.fillMaxWidth())
                        }
                    }
                }
            }
        }.getOrElse {
            // WIDGETS_V6 rejects ops outside the launcher's vocabulary —
            // log and skip rather than crashing the host process.
            Log.w(TAG, "widgets-profile capture failed for id=$widgetId type=${entry.card.type}", it)
            return
        }

        val instructions = RemoteViews.DrawInstructions.Builder(listOf(captured.bytes)).build()
        appWidgetManager.updateAppWidget(widgetId, RemoteViews(instructions))
    }

    private fun loadThemeChoice(context: Context): Pair<ThemeStyle, Boolean> = runBlocking {
        val prefs = context.terrazzoGraph().preferencesStore
        val style = prefs.themeStyleNow().toStyle()
        val dark = when (prefs.darkModeNow()) {
            DarkModePref.Follow -> {
                val ui = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                ui == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            DarkModePref.Light -> false
            DarkModePref.Dark -> true
        }
        style to dark
    }

    private fun ThemePref.toStyle(): ThemeStyle = when (this) {
        ThemePref.Material3 -> ThemeStyle.Material3
        ThemePref.TerrazzoHome -> ThemeStyle.TerrazzoHome
        ThemePref.TerrazzoMushroom -> ThemeStyle.TerrazzoMushroom
        ThemePref.TerrazzoMinimalist -> ThemeStyle.TerrazzoMinimalist
        ThemePref.TerrazzoKiosk -> ThemeStyle.TerrazzoKiosk
    }

    private fun dpToPx(context: Context, dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    private companion object {
        val EMPTY_SNAPSHOT = HaSnapshot()
        const val WIDGET_WIDTH_DP = 320
        const val TAG = "TerrazzoWidgetProvider"
    }
}
