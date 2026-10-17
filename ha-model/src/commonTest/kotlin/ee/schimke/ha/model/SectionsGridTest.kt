package ee.schimke.ha.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SectionsGrid] against the HA frontend's `hui-sections-view` rules. Widths are the grid's
 * available width; the gap is the host's column gap.
 */
class SectionsGridTest {

  private val gap = 16f

  private fun spans(vararg columnSpans: Int?) = columnSpans.map { SectionSpan(columnSpan = it) }

  private fun List<GridPlacement>.cells() = map { Triple(it.row, it.column, it.columnSpan) }

  @Test
  fun fittingColumns_followsHaMinimumColumnWidth() {
    // floor((w + gap) / (320 + gap))
    assertEquals(1, SectionsGrid.fittingColumns(388f, gap)) // phone portrait
    assertEquals(1, SectionsGrid.fittingColumns(655f, gap))
    assertEquals(2, SectionsGrid.fittingColumns(656f, gap))
    assertEquals(2, SectionsGrid.fittingColumns(700f, gap)) // phone landscape (732 dp window)
    assertEquals(2, SectionsGrid.fittingColumns(784f, gap)) // 800 dp tablet preview
    assertEquals(3, SectionsGrid.fittingColumns(1232f, gap)) // 1280 dp tablet
    assertEquals(4, SectionsGrid.fittingColumns(1328f, gap))
    assertEquals(6, SectionsGrid.fittingColumns(2000f, gap)) // unclamped; max_columns clamps later
    assertEquals(1, SectionsGrid.fittingColumns(0f, gap))
  }

  @Test
  fun columnCount_isClampedToMaxColumns_defaultFour() {
    val six = spans(1, 1, 1, 1, 1, 1)
    assertEquals(4, SectionsGrid.layout(six, 3000f, gap).columnCount)
    assertEquals(2, SectionsGrid.layout(six, 3000f, gap, maxColumns = 2).columnCount)
    // A nonsensical max_columns still leaves one column.
    assertEquals(1, SectionsGrid.layout(six, 3000f, gap, maxColumns = 0).columnCount)
  }

  @Test
  fun columnCount_neverExceedsTheSumOfSpans() {
    // F2: one span-1 section on a tablet is one centred column, not a column beside an empty one.
    val one = SectionsGrid.layout(spans(null), 784f, gap)
    assertEquals(1, one.columnCount)
    assertTrue(one.singleTrack)
    assertEquals(SectionsGrid.COLUMN_MAX_WIDTH_DP, one.maxWidthDp)

    // Spans count, not sections: one span-2 section uses both columns of a tablet.
    val wide = SectionsGrid.layout(spans(2), 784f, gap)
    assertEquals(2, wide.columnCount)
    assertEquals(listOf(Triple(0, 0, 2)), wide.placements.cells())
  }

  @Test
  fun columnSpan_isClampedToTheColumnsAvailable() {
    // Every demo dashboard section is `column_span: 4`; on a 2-column tablet each spans both.
    val layout = SectionsGrid.layout(spans(4, 4), 784f, gap)
    assertEquals(2, layout.columnCount)
    assertEquals(listOf(Triple(0, 0, 2), Triple(1, 0, 2)), layout.placements.cells())
    assertTrue(layout.singleTrack)

    // On a phone everything is one column.
    val phone = SectionsGrid.layout(spans(4, 2, null), 388f, gap)
    assertEquals(1, phone.columnCount)
    assertEquals(listOf(0, 1, 2), phone.placements.map { it.row })
    assertTrue(phone.placements.all { it.columnSpan == 1 })
  }

  @Test
  fun placement_isSparseRowFlowInAuthoredOrder() {
    // 3 columns: [1][2 2] / [3 3 3] / [1]...
    val layout = SectionsGrid.layout(spans(1, 2, 3, 1, 1), 1232f, gap)
    assertEquals(3, layout.columnCount)
    assertEquals(
      listOf(Triple(0, 0, 1), Triple(0, 1, 2), Triple(1, 0, 3), Triple(2, 0, 1), Triple(2, 1, 1)),
      layout.placements.cells(),
    )
    assertFalse(layout.singleTrack)
    assertEquals(3, layout.rowCount)
  }

  @Test
  fun sparsePlacement_leavesHolesThatDensePlacementBackFills() {
    // 3 columns: a span-2, a span-2 that does not fit beside it, then a span-1.
    val items = listOf(GridSpan(2), GridSpan(2), GridSpan(1))
    assertEquals(
      listOf(Triple(0, 0, 2), Triple(1, 0, 2), Triple(1, 2, 1)),
      SectionsGrid.placeAuto(items, columns = 3).cells(),
    )
    assertEquals(
      listOf(Triple(0, 0, 2), Triple(1, 0, 2), Triple(0, 2, 1)),
      SectionsGrid.placeAuto(items, columns = 3, dense = true).cells(),
    )
  }

  @Test
  fun rowSpan_reservesCellsInLaterRows() {
    val placements =
      SectionsGrid.placeAuto(
        listOf(GridSpan(1, rowSpan = 2), GridSpan(1), GridSpan(1), GridSpan(1)),
        columns = 2,
      )
    // The tall section holds column 0 of rows 0 and 1, so the third section goes beside it.
    assertEquals(
      listOf(Triple(0, 0, 1), Triple(0, 1, 1), Triple(1, 1, 1), Triple(2, 0, 1)),
      placements.cells(),
    )
    assertEquals(2, placements[0].rowSpan)
  }

  @Test
  fun layout_readsTheViewConfig() {
    val view =
      View(
        type = "sections",
        maxColumns = 1,
        sections = listOf(Section(columnSpan = 2), Section()),
        denseSectionPlacement = true,
      )
    val layout = SectionsGrid.layout(view, 2000f, gap)
    assertEquals(1, layout.columnCount)
    assertEquals(listOf(0, 1), layout.placements.map { it.row })
  }

  @Test
  fun maxWidth_isHaColumnMaxWidthTimesColumns() {
    val layout = SectionsGrid.layout(spans(1, 1, 1), 3000f, gap)
    assertEquals(3, layout.columnCount)
    assertEquals(3 * 500f + 2 * gap, layout.maxWidthDp)
  }

  @Test
  fun readableCap_appliesOnlyToSingleTrackLayouts() {
    // F4: a landscape phone (Medium) with full-span sections reads as one capped column.
    val cap = SectionsGrid.readableMaxWidthDp(732f)
    assertEquals(SectionsGrid.MEDIUM_MAX_CONTENT_WIDTH_DP, cap)
    val landscape = SectionsGrid.layout(spans(4), 700f, gap, singleTrackMaxWidthDp = cap)
    assertTrue(landscape.singleTrack)
    assertEquals(600f, landscape.maxWidthDp)

    // Side-by-side sections keep HA's width.
    val sideBySide = SectionsGrid.layout(spans(1, 1), 700f, gap, singleTrackMaxWidthDp = cap)
    assertFalse(sideBySide.singleTrack)
    assertEquals(2 * 500f + gap, sideBySide.maxWidthDp)

    // The cap never widens HA's own single-column limit.
    val narrow = SectionsGrid.layout(spans(1), 700f, gap, singleTrackMaxWidthDp = 840f)
    assertEquals(500f, narrow.maxWidthDp)
  }

  @Test
  fun readableMaxWidth_followsWindowSizeClasses() {
    assertEquals(null, SectionsGrid.readableMaxWidthDp(412f))
    assertEquals(600f, SectionsGrid.readableMaxWidthDp(600f))
    assertEquals(600f, SectionsGrid.readableMaxWidthDp(839f))
    assertEquals(840f, SectionsGrid.readableMaxWidthDp(840f))
  }

  @Test
  fun emptyView_hasOneColumnAndNoPlacements() {
    val layout = SectionsGrid.layout(emptyList(), 1000f, gap)
    assertEquals(1, layout.columnCount)
    assertEquals(0, layout.rowCount)
    assertTrue(layout.singleTrack)
  }
}
