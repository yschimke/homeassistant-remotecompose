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
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.SectionCardGrid
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [LovelaceSectionCardGrid] draws [SectionCardGrid] plans: cell widths, row tops, row spans. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LovelaceSectionCardGridTest {
  @get:Rule val compose = createComposeRule()

  private fun card(json: String): CardConfig {
    val obj = Json.parseToJsonElement(json).jsonObject
    return CardConfig(obj["type"].toString().trim('"'), obj)
  }

  @Test
  fun tilesShareARowAndFullWidthCardsSpanIt() {
    // 12 columns of 18 dp with 8 dp gaps in a 304 dp section: a tile is 6·18 + 5·8 = 148 dp.
    val plan =
      SectionCardGrid.plan(
        listOf(
          card("""{"type":"heading"}"""),
          card("""{"type":"tile"}"""),
          card("""{"type":"tile"}"""),
          card("""{"type":"history-graph"}"""),
        )
      )
    // Natural heights: heading 30, tiles 50 and 60, graph 100.
    val heights = listOf(30, 50, 60, 100)
    compose.setContent {
      Box(Modifier.width(304.dp)) {
        LovelaceSectionCardGrid(plan = plan) {
          heights.forEachIndexed { i, h -> Box(Modifier.height(h.dp).testTag("c$i")) }
        }
      }
    }

    compose.onNodeWithTag("c0").assertWidthIsEqualTo(304.dp)
    compose.onNodeWithTag("c1").assertLeftPositionInRootIsEqualTo(0.dp)
    compose.onNodeWithTag("c1").assertWidthIsEqualTo(148.dp)
    compose.onNodeWithTag("c2").assertLeftPositionInRootIsEqualTo(156.dp)
    compose.onNodeWithTag("c2").assertWidthIsEqualTo(148.dp)
    // Row 1 is as tall as the taller tile; both sit at its top, at their natural heights.
    compose.onNodeWithTag("c1").assertTopPositionInRootIsEqualTo(38.dp)
    compose.onNodeWithTag("c1").assertHeightIsEqualTo(50.dp)
    compose.onNodeWithTag("c3").assertTopPositionInRootIsEqualTo(106.dp)
    compose.onNodeWithTag("c3").assertWidthIsEqualTo(304.dp)
    compose.onRoot().assertHeightIsEqualTo(206.dp)
  }

  @Test
  fun rowSpansReserveRows_andATallSpanningCardGrowsEveryRowItSpans() {
    // entity (6×2) beside two tiles (6×1): the second tile goes under the first.
    val plan =
      SectionCardGrid.plan(
        listOf(
          card("""{"type":"entity"}"""),
          card("""{"type":"tile"}"""),
          card("""{"type":"tile"}"""),
        )
      )
    val heights = listOf(150, 56, 50)
    compose.setContent {
      Box(Modifier.width(304.dp)) {
        LovelaceSectionCardGrid(plan = plan, columnGap = 8.dp, rowGap = 8.dp) {
          heights.forEachIndexed { i, h -> Box(Modifier.height(h.dp).testTag("c$i")) }
        }
      }
    }

    // The 150 dp entity card spans rows 0–1 (56 + 8 + 50 = 114). As in CSS grid, the 36 dp it
    // still needs is shared by both rows: 74 dp and 68 dp, so the second tile starts at 82 dp.
    compose.onNodeWithTag("c1").assertTopPositionInRootIsEqualTo(0.dp)
    compose.onNodeWithTag("c1").assertLeftPositionInRootIsEqualTo(156.dp)
    compose.onNodeWithTag("c2").assertTopPositionInRootIsEqualTo(82.dp)
    compose.onNodeWithTag("c2").assertLeftPositionInRootIsEqualTo(156.dp)
    compose.onNodeWithTag("c0").assertTopPositionInRootIsEqualTo(0.dp)
    compose.onNodeWithTag("c0").assertHeightIsEqualTo(150.dp)
    compose.onRoot().assertHeightIsEqualTo(150.dp)
  }
}
