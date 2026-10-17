package ee.schimke.ha.rc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
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
import ee.schimke.ha.model.SectionFlowItem
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [LovelaceSectionCardFlow] draws [ee.schimke.ha.model.SectionCardFlow] rows at the real width. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h1200dp-mdpi")
class LovelaceSectionCardFlowTest {
  @get:Rule val compose = createComposeRule()

  // A heading, two tiles (140 dp minimum) and a history graph (240 dp).
  private val items =
    listOf(
      SectionFlowItem(120f, ownRow = true),
      SectionFlowItem(140f),
      SectionFlowItem(140f),
      SectionFlowItem(240f),
    )
  // Natural heights: heading 30, tiles 50 and 60, graph 100.
  private val heights = listOf(30, 50, 60, 100)

  @Test
  fun onAPhoneTheTilesShareARowAndTheGraphWraps() {
    compose.setContent {
      Box(Modifier.width(328.dp)) {
        LovelaceSectionCardFlow(items) {
          heights.forEachIndexed { i, h -> Box(Modifier.height(h.dp).testTag("c$i")) }
        }
      }
    }
    compose.onNodeWithTag("c0").assertWidthIsEqualTo(328.dp)
    // Two equal tiles: (328 − 8) / 2 = 160 dp each.
    compose.onNodeWithTag("c1").assertTopPositionInRootIsEqualTo(38.dp)
    compose.onNodeWithTag("c1").assertWidthIsEqualTo(160.dp)
    compose.onNodeWithTag("c2").assertLeftPositionInRootIsEqualTo(168.dp)
    compose.onNodeWithTag("c2").assertWidthIsEqualTo(160.dp)
    // The row is as tall as the taller tile; the shorter keeps its natural height.
    compose.onNodeWithTag("c1").assertHeightIsEqualTo(50.dp)
    compose.onNodeWithTag("c3").assertTopPositionInRootIsEqualTo(106.dp)
    compose.onNodeWithTag("c3").assertWidthIsEqualTo(328.dp)
    compose.onRoot().assertHeightIsEqualTo(206.dp)
  }

  @Test
  fun aWiderSectionReflowsTheSameCardsIntoFewerRows() {
    val width = mutableStateOf(328)
    compose.setContent {
      Box(Modifier.width(width.value.dp)) {
        LovelaceSectionCardFlow(items) {
          heights.forEachIndexed { i, h -> Box(Modifier.height(h.dp).testTag("c$i")) }
        }
      }
    }
    width.value = 560
    compose.waitForIdle()
    // 140 + 8 + 140 + 8 + 240 = 536 ≤ 560: tiles and graph share a row. The 544 dp of room is
    // split 140 : 140 : 240, so the graph gets the largest part.
    compose.onNodeWithTag("c3").assertTopPositionInRootIsEqualTo(38.dp)
    compose.onNodeWithTag("c1").assertWidthIsEqualTo(146.dp)
    compose.onNodeWithTag("c3").assertLeftPositionInRootIsEqualTo(308.dp)
    compose.onNodeWithTag("c3").assertWidthIsEqualTo(252.dp)
    compose.onRoot().assertHeightIsEqualTo(138.dp)
  }
}
