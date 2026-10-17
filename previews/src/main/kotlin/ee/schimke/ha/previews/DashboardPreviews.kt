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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.Section
import ee.schimke.ha.model.SectionsGrid
import ee.schimke.ha.model.View
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.LovelaceSectionCardFlow
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
import ee.schimke.ha.rc.sectionFlowItems
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

/*
 * Per-view previews. The whole-dashboard previews above stop at [MOBILE_HEIGHT] / [TABLET_HEIGHT],
 * so a board's second and later views, and the lower part of a long first view, never rendered and
 * the preview diff could not see changes there. Each preview below renders one view on its own
 * canvas, so CI baselines cover every view. The whole-dashboard previews stay as they are, so
 * their baselines remain comparable.
 *
 * Each canvas is the view's measured content height (noted above each preview) plus 150-250 dp,
 * rounded up to 100 dp. That is why these are explicit functions rather than a
 * `@PreviewParameter` fan-out: a fan-out shares one `heightDp`, so every view would pay for the
 * tallest. The tallest phone canvas (3900 dp) is 1079 x 10238 px, about 44 MB as ARGB; the
 * tallest tablet canvas (3700 dp) is 2100 x 9713 px, about 82 MB. Both fit the render task's 2 GiB
 * heap (see `previews/build.gradle.kts`) and stay below Android's 100 MiB limit on drawing a
 * bitmap through a `RecordingCanvas`.
 *
 * Coverage:
 * - Mobile: every view of the multi-view boards (3D printing, energy, meshcore) and the
 *   single-view boards whose whole-dashboard preview crops (climate, github). Networks and security
 *   already fit their whole-dashboard previews in full (1358 dp and 1574 dp of 1800), so they get
 *   no per-view copy.
 * - Tablet: only 3D printing's P2s and Filament views. At 800 dp the sections grid has two
 *   columns and every demo section spans at least two, so a tablet view is the phone layout capped
 *   at the medium readable width: same order, same content height. These two carry the card types
 *   no first view shows at that width (the Bambu print status / control cards, the picture-entity
 *   grid, the AMS and spool cards); A1 Mini repeats P2s's structure.
 *
 * When a demo board gains cards and a view outgrows its canvas, the bottom crops (the content
 * scrolls inside the canvas), so raise that view's height with the board change.
 */

// Content ends at 1568 dp.
@Preview(
  name = "dashboard 3d-printing view 1 Printers — mobile",
  widthDp = MOBILE_WIDTH,
  heightDp = 1800,
)
@Composable
fun DashboardView3dPrintingPrintersMobile() = DashboardViewPreview("3d_printing", viewIndex = 0)

// Content ends at 3481 dp.
@Preview(
  name = "dashboard 3d-printing view 2 P2s — mobile",
  widthDp = MOBILE_WIDTH,
  heightDp = 3700,
)
@Composable
fun DashboardView3dPrintingP2sMobile() = DashboardViewPreview("3d_printing", viewIndex = 1)

// Content ends at 3481 dp.
@Preview(
  name = "dashboard 3d-printing view 2 P2s — tablet",
  widthDp = TABLET_WIDTH,
  heightDp = 3700,
)
@Composable
fun DashboardView3dPrintingP2sTablet() = DashboardViewPreview("3d_printing", viewIndex = 1)

// Content ends at 3693 dp.
@Preview(
  name = "dashboard 3d-printing view 3 A1 Mini — mobile",
  widthDp = MOBILE_WIDTH,
  heightDp = 3900,
)
@Composable
fun DashboardView3dPrintingA1MiniMobile() = DashboardViewPreview("3d_printing", viewIndex = 2)

// Content ends at 2018 dp.
@Preview(
  name = "dashboard 3d-printing view 4 Filament — mobile",
  widthDp = MOBILE_WIDTH,
  heightDp = 2200,
)
@Composable
fun DashboardView3dPrintingFilamentMobile() = DashboardViewPreview("3d_printing", viewIndex = 3)

// Content ends at 2018 dp.
@Preview(
  name = "dashboard 3d-printing view 4 Filament — tablet",
  widthDp = TABLET_WIDTH,
  heightDp = 2200,
)
@Composable
fun DashboardView3dPrintingFilamentTablet() = DashboardViewPreview("3d_printing", viewIndex = 3)

// Content ends at 2136 dp.
@Preview(
  name = "dashboard climate view 1 Home — mobile",
  widthDp = MOBILE_WIDTH,
  heightDp = 2300,
)
@Composable
fun DashboardViewClimateHomeMobile() = DashboardViewPreview("climate", viewIndex = 0)

// Content ends at 1850 dp.
@Preview(
  name = "dashboard energy view 1 Overview — mobile",
  widthDp = MOBILE_WIDTH,
  heightDp = 2000,
)
@Composable
fun DashboardViewEnergyOverviewMobile() = DashboardViewPreview("energy", viewIndex = 0)

// Content ends at 976 dp.
@Preview(
  name = "dashboard energy view 2 Sun — mobile",
  widthDp = MOBILE_WIDTH,
  heightDp = 1200,
)
@Composable
fun DashboardViewEnergySunMobile() = DashboardViewPreview("energy", viewIndex = 1)

// Content ends at 3096 dp.
@Preview(
  name = "dashboard github view 1 Projects — mobile",
  widthDp = MOBILE_WIDTH,
  heightDp = 3300,
)
@Composable
fun DashboardViewGithubProjectsMobile() = DashboardViewPreview("github", viewIndex = 0)

// Content ends at 2041 dp.
@Preview(
  name = "dashboard meshcore view 1 Overview — mobile",
  widthDp = MOBILE_WIDTH,
  heightDp = 2200,
)
@Composable
fun DashboardViewMeshcoreOverviewMobile() = DashboardViewPreview("meshcore", viewIndex = 0)

// Content ends at 1689 dp.
@Preview(
  name = "dashboard meshcore view 2 Solar Repeater — mobile",
  widthDp = MOBILE_WIDTH,
  heightDp = 1900,
)
@Composable
fun DashboardViewMeshcoreSolarRepeaterMobile() = DashboardViewPreview("meshcore", viewIndex = 1)

// Content ends at 1555 dp.
@Preview(
  name = "dashboard meshcore view 3 Room Server — mobile",
  widthDp = MOBILE_WIDTH,
  heightDp = 1800,
)
@Composable
fun DashboardViewMeshcoreRoomServerMobile() = DashboardViewPreview("meshcore", viewIndex = 2)

// Content ends at 791 dp.
@Preview(
  name = "dashboard meshcore view 4 Controls — mobile",
  widthDp = MOBILE_WIDTH,
  heightDp = 1000,
)
@Composable
fun DashboardViewMeshcoreControlsMobile() = DashboardViewPreview("meshcore", viewIndex = 3)

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

/**
 * The climate board as HA's own sections dashboards are authored: each `grid` card's children are
 * lifted into the section and the demo's `grid_options: { columns: full }` dropped, so every card
 * takes its type's HA default on the section grid ([SectionCardGrid]) — tiles two to a row on a
 * phone-wide section, history graphs and pictures full width.
 */
@Preview(
  name = "dashboard section card grid — mobile",
  widthDp = MOBILE_WIDTH,
  heightDp = MOBILE_HEIGHT,
)
@Composable
fun DashboardSectionCardGridMobile() = DashboardPreview("climate", ::liftGridCards)

/** [DashboardSectionCardGridMobile] on the tablet canvas: sections span two view columns here. */
@Preview(
  name = "dashboard section card grid — tablet",
  widthDp = TABLET_WIDTH,
  heightDp = TABLET_HEIGHT,
)
@Composable
fun DashboardSectionCardGridTablet() = DashboardPreview("climate", ::liftGridCards)

private fun liftGridCards(view: View): View =
  view.copy(
    sections =
      view.sections.map { section ->
        section.copy(
          cards =
            section.cards.flatMap { card ->
              val children =
                if (card.type == "grid") {
                  (card.raw["cards"] as? JsonArray)?.mapNotNull { child ->
                    val obj = child as? JsonObject ?: return@mapNotNull null
                    val type = (obj["type"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    CardConfig(type, obj)
                  } ?: emptyList()
                } else {
                  listOf(card)
                }
              children.map { it.copy(raw = JsonObject(it.raw - "grid_options")) }
            }
        )
      }
  )

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

/** One view of a demo dashboard on its own canvas, so views past the first get a render. */
@Composable
private fun DashboardViewPreview(name: String, viewIndex: Int) {
  enableRemoteComposeWrapContent()
  val loaded = DashboardFixtures.load(name) ?: return
  val view = loaded.dashboard.views.getOrNull(viewIndex) ?: return
  val bitmapLoader = remember { DashboardPreviewBitmapLoader() }
  Column(
    modifier = Modifier.uiFillMaxWidth().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(modifier = Modifier.padding(16.dp)) {
      androidx.compose.material3.Text(
        text =
          "${loaded.dashboard.title ?: name} — view ${viewIndex + 1} of ${loaded.dashboard.views.size}",
        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
    }
    ViewBlock(view, loaded.snapshot, bitmapLoader)
  }
}

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
 * `DashboardViewScreen` uses, so the harness shows what the app does: HA's responsive column gap (8
 * dp up to a 600 dp canvas, 32 dp above) as padding either side and a 24 dp row gap, HA's column
 * count for the canvas width (320 dp minimum columns, `max_columns`, never more columns than total
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
    modifier = Modifier.uiFillMaxWidth(),
    contentAlignment = Alignment.TopCenter,
  ) {
    val grid =
      SectionsGrid.layout(
        view = view,
        viewWidthDp = maxWidth.value,
        singleTrackMaxWidthDp = SectionsGrid.readableMaxWidthDp(maxWidth.value),
      )
    Column(
      modifier =
        Modifier.padding(horizontal = grid.horizontalPaddingDp.dp)
          .widthIn(max = grid.maxWidthDp.dp)
          .uiFillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(grid.rowGapDp.dp),
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
        LovelaceSectionsGrid(grid = grid) {
          view.sections.forEachIndexed { i, section ->
            SectionBlock(section, snapshot, bitmapLoader)
          }
        }
      }
    }
  }
}

/**
 * One section: its title, then its cards in HA's order, laid out by their own designs' sizes
 * ([LovelaceSectionCardFlow], the same layout the app uses; design principle 9), each card measured
 * at exactly its share of the row.
 */
@Composable
private fun SectionBlock(
  section: Section,
  snapshot: HaSnapshot,
  bitmapLoader: BitmapLoader,
) {
  // Wrap the title + cards in a Surface tinted with
  // `HaTheme.Light.sectionBackground` so M3-elevation themes (Mushroom,
  // Kiosk, Material3) get a visible group. For these flat-HA previews
  // `sectionBackground == dashboardBackground`, so the wrap is a no-op
  // and the HA-reference pixel diff is unaffected.
  androidx.compose.material3.Surface(
    color = HaTheme.Light.sectionBackground,
    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    modifier = Modifier.uiFillMaxWidth(),
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
      val items =
        remember(section.cards, snapshot) {
          sectionFlowItems(
            section.cards,
            snapshot,
            defaultRegistry().withEnhancedShutter().withGarageShutter(),
          )
        }
      LovelaceSectionCardFlow(items = items, modifier = Modifier.uiFillMaxWidth()) {
        section.cards.forEach { card ->
          CardSlot(card, snapshot, bitmapLoader, horizontalPadding = 0.dp)
        }
      }
    }
  }
}

@Composable
private fun CardSlot(
  card: CardConfig,
  snapshot: HaSnapshot,
  bitmapLoader: BitmapLoader,
  horizontalPadding: Dp = 8.dp,
) {
  val registry = defaultRegistry().withEnhancedShutter().withGarageShutter()
  // Wrap-content via the adaptive player — slot wraps to the
  // document's intrinsic content height. Mirrors what
  // `DashboardViewScreen.CardSlot` does in production.
  Box(
    modifier =
      Modifier.uiFillMaxWidth()
        .heightIn(max = MAX_CARD_SLOT_HEIGHT_DP.dp)
        .padding(horizontal = horizontalPadding)
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
