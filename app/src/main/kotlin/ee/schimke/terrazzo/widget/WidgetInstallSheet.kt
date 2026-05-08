@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth as rcFillMaxWidth
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.cardHeightDp
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.widgetsProfile
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.widget.WidgetStore

/**
 * Bottom sheet shown when the user long-presses a card. Live preview
 * on top (rendered through `CachedCardPreview` with the same
 * [widgetsProfile] profile the home screen widget uses, so what you see is
 * what gets installed), then an "Add to Home Screen" button that calls
 * `requestPinAppWidget`.
 *
 * `CachedCardPreview` (vs. `RemotePreview`) buys two things here:
 *   - It pushes named-binding writes into the running player whenever
 *     [snapshot] changes, so the preview tracks live entity state
 *     instead of freezing on the first frame.
 *   - It captures once per `(card, theme, profile)` and keeps playing,
 *     so opening the sheet repeatedly for the same card is cheap.
 *
 * Install cap: disable the button once five widgets are already
 * installed. The cap-check number comes live from [WidgetStore] so
 * removing one in Settings re-enables the button without a restart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetInstallSheet(
    baseUrl: String,
    card: CardConfig,
    snapshot: HaSnapshot,
    onDismiss: () -> Unit,
    onMonitor: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val installer = remember { WidgetInstaller(context.applicationContext) }
    val store = LocalTerrazzoGraph.current.widgetStore
    val registry = remember { defaultRegistry().withEnhancedShutter() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var installedCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { installedCount = store.count() }

    val heightDp = remember(card, snapshot) { registry.cardHeightDp(card, snapshot) }
    val capHit = installedCount >= WidgetStore.MAX_WIDGETS
    val pinSupported = remember { installer.isSupported() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Add to Home Screen", style = MaterialTheme.typography.headlineSmall)
            Text(
                "This widget will keep itself up to date. ${WidgetStore.MAX_WIDGETS - installedCount} of " +
                    "${WidgetStore.MAX_WIDGETS} slots free.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Box(
                modifier = Modifier.fillMaxWidth().height(heightDp.dp),
            ) {
                CachedCardPreview(
                    cacheKey = WidgetPreviewCacheKey(card, HaTheme.Light, widgetsProfile),
                    profile = widgetsProfile,
                    card = card,
                    snapshot = snapshot,
                ) {
                    ProvideCardRegistry(registry) {
                        ProvideHaTheme(HaTheme.Light) {
                            RenderChild(card, snapshot, RemoteModifier.rcFillMaxWidth())
                        }
                    }
                }
            }

            if (!pinSupported) {
                Text(
                    "Your launcher doesn't support widget pinning. Install a widget " +
                        "from the system widget picker instead.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = {
                    installer.requestPin(baseUrl, card)
                    onDismiss()
                },
                enabled = pinSupported && !capHit,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (capHit) "All 5 slots in use" else "Add to Home Screen") }

            onMonitor?.let { start ->
                OutlinedButton(
                    onClick = {
                        start()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Monitor for 15 min") }
            }
        }
    }
}

/**
 * Cache key for the install-sheet preview. Mirrors the dashboard's
 * `CardSlotCacheKey` shape — snapshot is deliberately omitted so live
 * entity changes flow through named bindings instead of forcing a
 * re-encode (see `CachedCardPreview` for the binding push). Profile
 * is part of the key so the same card cached for the dashboard
 * (AndroidX-experimental) doesn't collide with the widget capture
 * (V7).
 */
private data class WidgetPreviewCacheKey(
    val card: CardConfig,
    val theme: HaTheme,
    val profile: Any,
)
