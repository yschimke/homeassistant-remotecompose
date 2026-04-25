@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import kotlinx.coroutines.delay

/**
 * The kiosk preview pane: real RemoteCompose cards rendered via the
 * same `CardConverter`/`CardPlayer` pipeline the phone uses. Demo mode
 * pulls cards from [TvDemoData] and ticks the snapshot every 2 s; live
 * mode placeholder shows a "Connect to Home Assistant" hint until the
 * full HA wiring lands.
 *
 * TV is dark-only on the wall, so we always pass `darkTheme = true`
 * into [haThemeFor]; the RC documents the converter emits will use the
 * same Terrazzo palette as the surrounding chrome.
 */
@Composable
fun TvKioskPreview(style: ThemeStyle, demoMode: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(demoMode) {
        if (!demoMode) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(2_000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(24.dp))
            .background(cs.surfaceContainerLow)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Header(style, demoMode)

        if (demoMode) {
            DemoCardGrid(style, snapshot = TvDemoData.snapshot(nowMs))
        } else {
            LivePlaceholder()
        }
    }
}

@Composable
private fun Header(style: ThemeStyle, demoMode: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Living room",
                style = MaterialTheme.typography.headlineMedium,
                color = cs.onSurface,
            )
            if (demoMode) {
                androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(cs.tertiary.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "DEMO",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.tertiary,
                    )
                }
            }
        }
        Text(
            text = "Preview · ${style.displayName}" + if (demoMode) " · animated" else "",
            style = MaterialTheme.typography.titleSmall,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun DemoCardGrid(style: ThemeStyle, snapshot: HaSnapshot) {
    // Same haTheme the phone widgets use, always dark for the kiosk.
    val haTheme = remember(style) { haThemeFor(style, darkTheme = true) }
    val registry = remember { defaultRegistry() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TvDemoData.cards.forEach { card ->
            val heightDp = cardHeightDp(registry, card, snapshot).coerceAtLeast(120)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heightDp.dp),
            ) {
                // Use RemotePreview (the same path the previews module
                // uses) instead of CardPlayer — it dodges the
                // RemoteCreationDisplayInfo plumbing CardPlayer wants
                // and renders cleanly inside Robolectric / compose-preview.
                RemotePreview(profile = androidXExperimental) {
                    ProvideCardRegistry(registry) {
                        ProvideHaTheme(haTheme) {
                            RenderChild(card, snapshot, RemoteModifier.rcFillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LivePlaceholder() {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Connect to Home Assistant",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
            )
            Text(
                text = "Live HA wiring lands in a follow-up — flip on Demo mode\n" +
                    "to preview the kiosk against animated fake state.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

private fun cardHeightDp(
    registry: ee.schimke.ha.rc.CardRegistry,
    card: CardConfig,
    snapshot: HaSnapshot,
): Int = registry.cardHeightDp(card, snapshot)
