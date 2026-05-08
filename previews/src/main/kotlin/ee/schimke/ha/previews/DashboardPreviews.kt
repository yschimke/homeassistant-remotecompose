@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import ee.schimke.ha.model.Section
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

/** Width threshold below which we collapse the sections grid to a
 *  single column — matches HA's web frontend behaviour, which packs
 *  sections only on viewports wide enough that one section keeps a
 *  reasonable per-card width (~380 dp). */
private const val SECTIONS_GRID_MIN_WIDTH_DP = 600

/** Per-column target width on tablet+. Sections fill `max_columns`
 *  columns up to whatever fits in the canvas at this width. */
private const val SECTIONS_COLUMN_TARGET_WIDTH_DP = 360

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
    if (view.type == "sections" && view.sections.isNotEmpty()) {
        SectionsView(view, snapshot)
    } else {
        // Legacy `type: panel` / `type: masonry` / unspecified — render
        // every card from view.cards + view.sections.cards as a single
        // vertical column. Same behaviour as before this change.
        val cards = view.cards + view.sections.flatMap { it.cards }
        cards.forEach { card -> CardSlot(card, snapshot) }
    }
}

/**
 * `type: sections` view layout. On viewports below
 * [SECTIONS_GRID_MIN_WIDTH_DP] we render single-column (mobile path);
 * on wider hosts we pack sections into N columns based on the canvas
 * width and the view's `max_columns:` config (default 4 per HA).
 *
 * Sections are distributed by walking left-to-right through columns,
 * appending to the column with the smallest current card-count — keeps
 * column heights roughly balanced without honouring `column_span` /
 * `row_span` yet (those are minor v2 polish).
 */
@Composable
private fun SectionsView(view: View, snapshot: HaSnapshot) {
    BoxWithConstraints(modifier = Modifier.uiFillMaxWidth()) {
        val widthDp = maxWidth.value.toInt()
        val maxColumns = view.maxColumns ?: 4
        val columnCount =
            if (widthDp < SECTIONS_GRID_MIN_WIDTH_DP) 1
            else (widthDp / SECTIONS_COLUMN_TARGET_WIDTH_DP).coerceIn(1, maxColumns)

        if (columnCount == 1) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                view.sections.forEach { section -> SectionBlock(section, snapshot) }
            }
        } else {
            val columns = packSections(view.sections, columnCount)
            Row(
                modifier = Modifier.uiFillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                columns.forEach { sections ->
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        sections.forEach { section -> SectionBlock(section, snapshot) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionBlock(section: Section, snapshot: HaSnapshot) {
    // Wrap the title + cards in a Surface tinted with
    // `HaTheme.Light.sectionBackground` so M3-elevation themes (Mushroom,
    // Kiosk, Material3) get a visible group. For these flat-HA previews
    // `sectionBackground == dashboardBackground`, so the wrap is a no-op
    // and the HA-reference pixel diff is unaffected.
    androidx.compose.material3.Surface(
        color = HaTheme.Light.sectionBackground,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        modifier = Modifier.uiFillMaxWidth().padding(horizontal = 4.dp),
    ) {
        androidx.compose.foundation.layout.Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            if (section.title != null) {
                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    androidx.compose.material3.Text(
                        text = section.title!!,
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    )
                }
            }
            section.cards.forEach { card -> CardSlot(card, snapshot) }
        }
    }
}

/** Greedy pack — append each section to the shortest column. Keeps
 *  column heights balanced without modelling per-card pixel heights. */
private fun packSections(sections: List<Section>, columnCount: Int): List<List<Section>> {
    val columns = MutableList(columnCount) { mutableListOf<Section>() }
    val cardCounts = MutableList(columnCount) { 0 }
    sections.forEach { section ->
        val target = cardCounts.indices.minBy { cardCounts[it] }
        columns[target] += section
        cardCounts[target] += section.cards.size.coerceAtLeast(1)
    }
    return columns
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
