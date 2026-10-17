package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.SectionsGrid
import ee.schimke.terrazzo.core.session.DemoData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/**
 * The demo boards through both config paths the app has — the live decoder (`HaClient` /
 * `OfflineCache` use `Dashboard.serializer()`) and the demo session's hand-written walker — into
 * [buildDashboardLayout] and HA's sections grid, at the widths the dashboard screen runs at.
 */
class SectionsLayoutTest {

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  private fun decodeBundled(slug: String): Dashboard {
    val text =
      DemoData::class
        .java
        .getResourceAsStream("/dashboards/$slug/lovelace_config.json")
        ?.bufferedReader()
        ?.use { it.readText() }
    assertNotNull(text, "missing bundled lovelace_config.json for $slug")
    return json.decodeFromString(Dashboard.serializer(), text)
  }

  @Test
  fun liveDecoder_readsHeaderAndSectionSpans() {
    val overview = decodeBundled("3d_printing").views.first()

    val header = assertNotNull(overview.header?.card, "3D printing overview header card")
    assertEquals("markdown", header.type)
    assertTrue(header.raw["content"]!!.jsonPrimitive.content.startsWith("# 🏭 3D Printing"))
    assertEquals(4, overview.maxColumns)
    assertEquals(listOf(2, 2), overview.sections.map { it.columnSpan })
  }

  @Test
  fun demoSession_andLiveDecoder_agreeOnSectionsLayoutFields() {
    for (summary in DemoData.dashboards) {
      val demo = DemoData.dashboard(summary.urlPath)
      val slug = summary.urlPath?.replace('-', '_') ?: "security"
      val live = decodeBundled(slug)
      assertEquals(live.views.size, demo.views.size, slug)
      live.views.zip(demo.views).forEachIndexed { i, (l, d) ->
        assertEquals(l.header, d.header, "$slug view $i header")
        assertEquals(l.maxColumns, d.maxColumns, "$slug view $i max_columns")
        assertEquals(
          l.sections.map { it.columnSpan to it.rowSpan },
          d.sections.map { it.columnSpan to it.rowSpan },
          "$slug view $i spans",
        )
      }
    }
  }

  @Test
  fun dashboardLayout_carriesHeaderAndSpans() {
    val layout = buildDashboardLayout(DemoData.dashboard("3d-printing"))
    val overview = layout.views.first()
    assertEquals("markdown", overview.header?.card?.type)
    assertEquals(listOf(2, 2), overview.sections.map { it.columnSpan })
    assertEquals(4, overview.maxColumns)
    // The AMS view has no header.
    assertEquals(null, layout.views.last().header)
  }

  @Test
  fun phoneLandscape_isOneHaColumn() {
    // Screen_DashboardView_PhoneLandscapeMedium: a 732 dp window. HA pads the view by its 32 dp
    // column gap either side, so (732 - 64 + 32) / (320 + 32) = 1.99 → one column, 500 dp wide.
    val security = buildDashboardLayout(DemoData.dashboard(null)).views.single()
    val grid = security.sectionsGrid(732f, 732f, SectionsGrid.readableMaxWidthDp(732f))
    assertEquals(1, grid.columnCount)
    assertTrue(grid.singleTrack)
    assertEquals(SectionsGrid.COLUMN_MAX_WIDTH_DP, grid.maxWidthDp)
    assertEquals(32f, grid.horizontalPaddingDp)
    assertEquals(32f, grid.columnGapDp)
    assertEquals(24f, grid.rowGapDp)
  }

  @Test
  fun phonePortrait_usesHaNarrowGap() {
    val security = buildDashboardLayout(DemoData.dashboard(null)).views.single()
    val grid = security.sectionsGrid(411f)
    assertEquals(1, grid.columnCount)
    assertEquals(8f, grid.columnGapDp)
    assertEquals(8f, grid.horizontalPaddingDp)
  }

  @Test
  fun tablet_columnSpanIsHonoured() {
    val tabletWidth = 1280f
    val cap = SectionsGrid.readableMaxWidthDp(1280f)

    // Climate: two `column_span: 4` sections stack full width instead of two unequal columns.
    val climate = buildDashboardLayout(DemoData.dashboard("climate")).views.single()
    val climateGrid = climate.sectionsGrid(tabletWidth, tabletWidth, cap)
    assertEquals(3, climateGrid.columnCount)
    assertEquals(listOf(0, 1), climateGrid.placements.map { it.row })
    assertTrue(climateGrid.placements.all { it.columnSpan == 3 })

    // 3D printing overview: two span-2 sections can't share a 3-column row (HA's sparse flow).
    val printing = buildDashboardLayout(DemoData.dashboard("3d-printing")).views.first()
    val printingGrid = printing.sectionsGrid(tabletWidth, tabletWidth, cap)
    assertEquals(listOf(0, 1), printingGrid.placements.map { it.row })
    assertTrue(printingGrid.placements.all { it.columnSpan == 2 })
    assertFalse(printingGrid.singleTrack)

    // Without spans the same two sections sit side by side.
    val spanOne = printing.copy(sections = printing.sections.map { it.copy(columnSpan = null) })
    val spanOneGrid = spanOne.sectionsGrid(tabletWidth, tabletWidth, cap)
    assertEquals(2, spanOneGrid.columnCount)
    assertEquals(listOf(0 to 0, 0 to 1), spanOneGrid.placements.map { it.row to it.column })
  }
}
