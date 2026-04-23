@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.widget

import android.content.Context
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.widgets.RemoteComposeWidget
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.core.widget.WidgetStore
import ee.schimke.terrazzo.terrazzoGraph
import kotlinx.coroutines.runBlocking

/**
 * Per-card home-screen widget. Extends the RemoteCompose
 * `AppWidgetProvider` scaffolding, which turns our `@Composable
 * Content(context, widgetId)` into `RemoteViews.DrawInstructions` and
 * routes click lambdas back through `onReceive` automatically.
 *
 * One provider class covers all installed Terrazzo widgets — each
 * pinned instance gets a unique `widgetId` that keys into [WidgetStore]
 * to find which HA card this instance should render.
 *
 * v1 scope:
 *   - Read the widget's card config + base URL from [WidgetStore]
 *     synchronously at render time.
 *   - Render with an empty snapshot (no cached state yet) — the card
 *     will show defaults until a future background worker writes a
 *     fresh snapshot and calls `updateWidgets(id, lambda)`.
 *
 * API floor: VANILLA_ICE_CREAM (Android 15 / API 35) — baked into
 * `RemoteComposeWidget.onUpdate` via `@RequiresApi`. The app's minSdk
 * of 36 already satisfies this.
 */
class TerrazzoWidgetProvider : RemoteComposeWidget(useCompose = true) {

    @Composable
    override fun Content(context: Context, widgetId: Int) {
        val entry = remember(context, widgetId) { loadEntry(context, widgetId) }
        if (entry == null) {
            // Not configured yet — nothing to draw. Happens momentarily
            // between pin + first config write. The widget will update
            // again once the app calls updateAppWidget().
            return
        }
        // Widgets pinned while the app was in demo mode carry the demo
        // baseUrl marker; render those against the current demo
        // snapshot so values are non-empty. Live-mode widgets keep the
        // empty default — they'll be refreshed by a future background
        // worker once we wire snapshot caching.
        val snapshot = if (DemoData.isDemo(entry.baseUrl)) DemoData.snapshot() else EMPTY_SNAPSHOT
        val registry = defaultRegistry()
        ProvideCardRegistry(registry) {
            ProvideHaTheme(HaTheme.Light) {
                RenderChild(entry.card, snapshot, RemoteModifier.fillMaxWidth())
            }
        }
    }

    private fun loadEntry(context: Context, widgetId: Int): WidgetStore.Entry? =
        runBlocking { context.terrazzoGraph().widgetStore.get(widgetId) }

    private companion object {
        val EMPTY_SNAPSHOT = HaSnapshot()
    }
}

// Non-composable `remember` alias — the RemoteComposeWidget capture
// runs inside a Compose composition, but we want a plain memoization
// keyed to a pair of inputs. Implemented via androidx.compose.runtime.
@Composable
private fun <T> remember(key1: Any?, key2: Any?, calculation: () -> T): T =
    androidx.compose.runtime.remember(key1, key2, calculation)
