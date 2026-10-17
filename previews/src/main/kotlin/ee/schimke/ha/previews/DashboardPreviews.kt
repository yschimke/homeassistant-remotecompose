@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth as uiFillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.Section
import ee.schimke.ha.model.SectionsGrid
import ee.schimke.ha.model.View
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.LovelaceSectionsGrid
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.cards.shutter.withGarageShutter
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.enableRemoteComposeWrapContent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Whole-dashboard previews — one column per card, stacked vertically. Used to compare
 * adaptive-layout candidates: render each captured dashboard at mobile (411 dp wide) and tablet
 * (800 dp wide) and look at the resulting PNGs side-by-side.
 *
 * Card heights flow from each document's intrinsic content via the CMP player (used by
 * `CachedCardPreview`), so vertical-stack / grid / sections cards size themselves to whatever they
 * actually drew. The whole dashboard renders inside one `verticalScroll` so previews longer than
 * the canvas crop instead of clipping silently.
 *
 * Skips rendering when the sibling [homeassistant-agents] checkout isn't present at the expected
 * path (see [DashboardFixtures]). The preview lambda short-circuits with a single placeholder
 * string in that case so CI runs of the previews module don't fail.
 */
private const val MOBILE_WIDTH = 411
private const val MOBILE_HEIGHT = 1800
private const val TABLET_WIDTH = 800
private const val TABLET_HEIGHT = 1800
private const val MAX_CARD_SLOT_HEIGHT_DP = 1800

@Preview(name = "dashboard 3d-printing — mobile", widthDp = MOBILE_WIDTH, heightDp = MOBILE_HEIGHT)
@Composable
fun Dashboard3dPrintingMobile() = DashboardPreview("3d_printing")

@Preview(name = "dashboard 3d-printing — tablet", widthDp = TABLET_WIDTH, heightDp = TABLET_HEIGHT)
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

/**
 * The climate board with every `column_span` removed (HA's default of 1), so the tablet canvas
 * exercises the side-by-side sections grid: two span-1 sections share a row, each one HA column
 * wide. The demo boards themselves are all `column_span: 4`, which stacks on a tablet.
 */
@Preview(
  name = "dashboard sections span 1 — tablet",
  widthDp = TABLET_WIDTH,
  heightDp = TABLET_HEIGHT,
)
@Composable
fun DashboardSectionsSpanOneTablet() =
  DashboardPreview("climate") { view ->
    view.copy(sections = view.sections.map { it.copy(columnSpan = null) })
  }

@Composable
private fun DashboardPreview(name: String, transformView: (View) -> View = { it }) {
  enableRemoteComposeWrapContent()
  val loaded =
    DashboardFixtures.load(name)?.let {
      it.copy(dashboard = it.dashboard.copy(views = it.dashboard.views.map(transformView)))
    } ?: return
  val bitmapLoader = remember { DashboardPreviewBitmapLoader() }
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
    loaded.dashboard.views.forEach { view -> ViewBlock(view, loaded.snapshot, bitmapLoader) }
  }
}

/** Gap between section columns and rows in the sections grid. */
private val SECTION_GAP = 12.dp

/** Horizontal padding either side of the sections grid. */
private val SECTIONS_GUTTER = 8.dp

@Composable
private fun ViewBlock(view: View, snapshot: HaSnapshot, bitmapLoader: BitmapLoader) {
  if (view.title != null) {
    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
      androidx.compose.material3.Text(
        text = view.title!!,
        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
      )
    }
  }
  if (view.type == "sections" && (view.sections.isNotEmpty() || view.header?.card != null)) {
    SectionsView(view, snapshot, bitmapLoader)
  } else {
    // Legacy `type: panel` / `type: masonry` / unspecified — render
    // every card from view.cards + view.sections.cards as a single
    // vertical column. Same behaviour as before this change.
    val cards = view.cards + view.sections.flatMap { it.cards }
    cards.forEach { card -> CardSlot(card, snapshot, bitmapLoader) }
  }
}

/**
 * `type: sections` view layout, placed by [SectionsGrid] — the same function the app's
 * `DashboardViewScreen` uses, so the harness shows what the app does: HA's column count for the
 * canvas width (320 dp minimum columns, `max_columns`, never more columns than total
 * `column_span`), each section spanning `min(column_span, columns)` in sparse row flow, the grid
 * capped at HA's 500 dp per column and centred. A view whose sections all span the full width takes
 * the window-size readable cap ([SectionsGrid.readableMaxWidthDp]), as the app does.
 *
 * The view's `header` card renders first, centred (HA's default `layout: center`) and at most
 * [SectionsGrid.HEADER_MAX_WIDTH_DP] wide.
 */
@Composable
private fun SectionsView(view: View, snapshot: HaSnapshot, bitmapLoader: BitmapLoader) {
  BoxWithConstraints(
    modifier = Modifier.uiFillMaxWidth().padding(horizontal = SECTIONS_GUTTER),
    contentAlignment = Alignment.TopCenter,
  ) {
    val grid =
      SectionsGrid.layout(
        view = view,
        availableWidthDp = maxWidth.value,
        columnGapDp = SECTION_GAP.value,
        singleTrackMaxWidthDp =
          SectionsGrid.readableMaxWidthDp(maxWidth.value + 2 * SECTIONS_GUTTER.value),
      )
    Column(
      modifier = Modifier.widthIn(max = grid.maxWidthDp.dp).uiFillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
      view.header?.card?.let { headerCard ->
        Box(
          modifier = Modifier.uiFillMaxWidth(),
          contentAlignment =
            if (view.header!!.centered) Alignment.TopCenter else Alignment.TopStart,
        ) {
          Box(Modifier.widthIn(max = SectionsGrid.HEADER_MAX_WIDTH_DP.dp)) {
            CardSlot(headerCard, snapshot, bitmapLoader)
          }
        }
      }
      if (view.sections.isNotEmpty()) {
        LovelaceSectionsGrid(grid = grid, columnGap = SECTION_GAP, rowGap = SECTION_GAP) {
          view.sections.forEach { section -> SectionBlock(section, snapshot, bitmapLoader) }
        }
      }
    }
  }
}

@Composable
private fun SectionBlock(section: Section, snapshot: HaSnapshot, bitmapLoader: BitmapLoader) {
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
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (section.title != null) {
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
          androidx.compose.material3.Text(
            text = section.title!!,
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
          )
        }
      }
      section.cards.forEach { card -> CardSlot(card, snapshot, bitmapLoader) }
    }
  }
}

@Composable
private fun CardSlot(card: CardConfig, snapshot: HaSnapshot, bitmapLoader: BitmapLoader) {
  val registry = defaultRegistry().withEnhancedShutter().withGarageShutter()
  // Wrap-content via the adaptive player — slot wraps to the
  // document's intrinsic content height. Mirrors what
  // `DashboardViewScreen.CardSlot` does in production.
  Box(
    modifier =
      Modifier.uiFillMaxWidth()
        .heightIn(max = MAX_CARD_SLOT_HEIGHT_DP.dp)
        .padding(horizontal = 8.dp)
  ) {
    CachedCardPreview(
      cacheKey = card,
      profile = androidXExperimentalWrap,
      modifier = Modifier.uiFillMaxWidth(),
      card = card,
      snapshot = snapshot,
      // Mirror `DashboardViewScreen.CardSlot`: cards that re-encode on a data signature bake their
      // live content, so pushing raw named bindings would clobber their per-card formatting.
      liveBindings = registry.cardDataSignature(card, snapshot) == null,
      bitmapLoader = bitmapLoader,
    ) {
      ProvideCardRegistry(registry) {
        ProvideHaTheme(HaTheme.Light) { RenderChild(card, snapshot, RemoteModifier.fillMaxWidth()) }
      }
    }
  }
}

private class DashboardPreviewBitmapLoader : BitmapLoader {
  private val bytes: ByteArray =
    ByteArrayOutputStream().use { out ->
      val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      val paint = Paint(Paint.ANTI_ALIAS_FLAG)
      paint.color = 0xFF1B5E20.toInt()
      canvas.drawRect(0f, 0f, 96f, 96f, paint)
      paint.color = 0xFFFFFFFF.toInt()
      canvas.drawCircle(48f, 48f, 30f, paint)
      paint.color = 0xFF1B5E20.toInt()
      canvas.drawCircle(48f, 48f, 14f, paint)
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
      bitmap.recycle()
      out.toByteArray()
    }

  override fun loadBitmap(name: String) = ByteArrayInputStream(bytes)
}
