package ee.schimke.ha.rc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.SectionSpan
import ee.schimke.ha.model.SectionsGrid
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [LovelaceSectionsGrid] draws [SectionsGrid] placements: span widths, row tops, row heights. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LovelaceSectionsGridTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun placesSectionsBySpanAndSizesRowsToTheirTallestSection() {
    // 3 columns of 100 dp with 10 dp gaps: [a][b b] / [c c c]
    val grid =
      SectionsGrid.layout(
        spans = listOf(SectionSpan(1), SectionSpan(2), SectionSpan(3)),
        availableWidthDp = 2000f,
        columnGapDp = 10f,
        maxColumns = 3,
      )
    val heights = listOf(40, 70, 20)
    compose.setContent {
      Box(Modifier.width(320.dp)) {
        LovelaceSectionsGrid(grid = grid, columnGap = 10.dp, rowGap = 10.dp) {
          heights.forEachIndexed { i, h -> Box(Modifier.height(h.dp).testTag("s$i")) }
        }
      }
    }

    compose.onNodeWithTag("s0").assertLeftPositionInRootIsEqualTo(0.dp)
    compose.onNodeWithTag("s0").assertWidthIsEqualTo(100.dp)
    compose.onNodeWithTag("s1").assertLeftPositionInRootIsEqualTo(110.dp)
    compose.onNodeWithTag("s1").assertWidthIsEqualTo(210.dp)
    // Sections align to the top of their row; the row is as tall as its tallest section.
    compose.onNodeWithTag("s0").assertTopPositionInRootIsEqualTo(0.dp)
    compose.onNodeWithTag("s0").assertHeightIsEqualTo(40.dp)
    compose.onNodeWithTag("s2").assertTopPositionInRootIsEqualTo(80.dp)
    compose.onNodeWithTag("s2").assertWidthIsEqualTo(320.dp)
    compose.onRoot().assertHeightIsEqualTo(100.dp)
  }

  @Test
  fun rowSpanGrowsOnlyTheLastSpannedRowWhenNeeded() {
    // 2 columns: a tall row-span-2 section beside two short ones.
    val grid =
      SectionsGrid.layout(
        spans = listOf(SectionSpan(1, rowSpan = 2), SectionSpan(1), SectionSpan(1)),
        availableWidthDp = 2000f,
        columnGapDp = 0f,
        maxColumns = 2,
      )
    val heights = listOf(150, 50, 50)
    compose.setContent {
      Box(Modifier.width(200.dp)) {
        LovelaceSectionsGrid(grid = grid, columnGap = 0.dp, rowGap = 10.dp) {
          heights.forEachIndexed { i, h -> Box(Modifier.height(h.dp).testTag("s$i")) }
        }
      }
    }

    compose.onNodeWithTag("s1").assertTopPositionInRootIsEqualTo(0.dp)
    // Row 0 is 50 dp; row 1 grows to 150 - 50 - 10 = 90 dp so the tall section fits.
    compose.onNodeWithTag("s2").assertTopPositionInRootIsEqualTo(60.dp)
    compose.onNodeWithTag("s2").assertLeftPositionInRootIsEqualTo(100.dp)
    compose.onRoot().assertHeightIsEqualTo(150.dp)
  }
}
