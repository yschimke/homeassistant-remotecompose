@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth as uiFillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.tooling.preview.RemotePreview
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.View
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.cards.shutter.withGarageShutter
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme

/**
 * Whole-dashboard previews — one column per card, stacked vertically.
 * Used to compare adaptive-layout candidates: render each captured
 * dashboard at mobile (411 dp wide) and tablet (800 dp wide) and look
 * at the resulting PNGs side-by-side.
 *
 * Card heights come from each converter's `naturalHeightDp(card,
 * snapshot)`, so vertical-stack / grid / sections cards size
 * themselves correctly. The whole dashboard renders inside one
 * `verticalScroll` so previews longer than the canvas crop instead of
 * clipping silently.
 *
 * Skips rendering when the sibling [homeassistant-agents] checkout
 * isn't present at the expected path (see [DashboardFixtures]). The
 * preview lambda short-circuits with a single placeholder string in
 * that case so CI runs of the previews module don't fail.
 */
private const val MOBILE_WIDTH = 411
private const val MOBILE_HEIGHT = 1800
private const val TABLET_WIDTH = 800
private const val TABLET_HEIGHT = 1800

@Preview(
    name = "dashboard 3d-printing — mobile",
    widthDp = MOBILE_WIDTH,
    heightDp = MOBILE_HEIGHT,
)
@Composable
fun Dashboard3dPrintingMobile() = DashboardPreview("3d_printing")

@Preview(
    name = "dashboard 3d-printing — tablet",
    widthDp = TABLET_WIDTH,
    heightDp = TABLET_HEIGHT,
)
@Composable
fun Dashboard3dPrintingTablet() = DashboardPreview("3d_printing")

@Preview(name = "dashboard climate — mobile", widthDp = MOBILE_WIDTH, heightDp = MOBILE_HEIGHT)
@Composable
fun DashboardClimateMobile() = DashboardPreview("climate")

@Preview(name = "dashboard climate — tablet", widthDp = TABLET_WIDTH, heightDp = TABLET_HEIGHT)
@Composable
fun DashboardClimateTablet() = DashboardPreview("climate")

@Preview(name = "dashboard energy — mobile", widthDp = MOBILE_WIDTH, heightDp = MOBILE_HEIGHT)
@Composable
fun DashboardEnergyMobile() = DashboardPreview("energy")

@Preview(name = "dashboard energy — tablet", widthDp = TABLET_WIDTH, heightDp = TABLET_HEIGHT)
@Composable
fun DashboardEnergyTablet() = DashboardPreview("energy")

@Preview(name = "dashboard github — mobile", widthDp = MOBILE_WIDTH, heightDp = MOBILE_HEIGHT)
@Composable
fun DashboardGithubMobile() = DashboardPreview("github")

@Preview(name = "dashboard github — tablet", widthDp = TABLET_WIDTH, heightDp = TABLET_HEIGHT)
@Composable
fun DashboardGithubTablet() = DashboardPreview("github")

@Preview(name = "dashboard meshcore — mobile", widthDp = MOBILE_WIDTH, heightDp = MOBILE_HEIGHT)
@Composable
fun DashboardMeshcoreMobile() = DashboardPreview("meshcore")

@Preview(name = "dashboard meshcore — tablet", widthDp = TABLET_WIDTH, heightDp = TABLET_HEIGHT)
@Composable
fun DashboardMeshcoreTablet() = DashboardPreview("meshcore")

@Preview(name = "dashboard networks — mobile", widthDp = MOBILE_WIDTH, heightDp = MOBILE_HEIGHT)
@Composable
fun DashboardNetworksMobile() = DashboardPreview("networks")

@Preview(name = "dashboard networks — tablet", widthDp = TABLET_WIDTH, heightDp = TABLET_HEIGHT)
@Composable
fun DashboardNetworksTablet() = DashboardPreview("networks")

@Preview(name = "dashboard security — mobile", widthDp = MOBILE_WIDTH, heightDp = MOBILE_HEIGHT)
@Composable
fun DashboardSecurityMobile() = DashboardPreview("security")

@Preview(name = "dashboard security — tablet", widthDp = TABLET_WIDTH, heightDp = TABLET_HEIGHT)
@Composable
fun DashboardSecurityTablet() = DashboardPreview("security")

@Composable
private fun DashboardPreview(name: String) {
    val loaded = DashboardFixtures.load(name) ?: return
    Column(
        modifier = Modifier.uiFillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            androidx.compose.material3.Text(
                text = "${loaded.dashboard.title ?: name} — ${loaded.dashboard.views.size} view(s)",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        loaded.dashboard.views.forEach { view ->
            ViewBlock(view, loaded.snapshot)
        }
    }
}

@Composable
private fun ViewBlock(view: View, snapshot: HaSnapshot) {
    if (view.title != null) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            androidx.compose.material3.Text(
                text = view.title!!,
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
            )
        }
    }
    val cards = view.cards + view.sections.flatMap { it.cards }
    cards.forEach { card -> CardSlot(card, snapshot) }
}

@Composable
private fun CardSlot(card: CardConfig, snapshot: HaSnapshot) {
    val registry = defaultRegistry().withEnhancedShutter().withGarageShutter()
    val converter = registry.get(card.type)
    val height = (converter?.naturalHeightDp(card, snapshot) ?: 96)
        .coerceAtLeast(48)
    Box(modifier = Modifier.uiFillMaxWidth().height(height.dp).padding(horizontal = 8.dp)) {
        RemotePreview(profile = androidXExperimental) {
            ProvideCardRegistry(registry) {
                ProvideHaTheme(HaTheme.Light) {
                    RenderChild(card, snapshot, RemoteModifier.fillMaxWidth())
                }
            }
        }
    }
}
