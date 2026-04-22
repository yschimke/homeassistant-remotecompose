@file:Suppress("RestrictedApiAndroidX")

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
        val registry = defaultRegistry()
        ProvideCardRegistry(registry) {
            ProvideHaTheme(HaTheme.Light) {
                RenderChild(entry.card, EMPTY_SNAPSHOT, RemoteModifier.fillMaxWidth())
            }
        }
    }

    private fun loadEntry(context: Context, widgetId: Int): WidgetStore.Entry? =
        runBlocking { WidgetStore(context.applicationContext).get(widgetId) }

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
