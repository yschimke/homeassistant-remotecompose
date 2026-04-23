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
import androidx.compose.remote.tooling.preview.RemotePreview
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.cardHeightDp
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.widget.WidgetStore

/**
 * Bottom sheet shown when the user long-presses a card. Live preview
 * on top (rendered through the exact same `RemotePreview` the home
 * screen widget uses — so what you see is what gets installed), then
 * an "Add to Home Screen" button that calls `requestPinAppWidget`.
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
    val registry = remember { defaultRegistry() }
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
                RemotePreview(profile = androidXExperimental) {
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
