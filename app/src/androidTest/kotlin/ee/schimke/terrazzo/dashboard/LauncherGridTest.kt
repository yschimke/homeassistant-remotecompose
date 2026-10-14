package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ee.schimke.terrazzo.widget.LauncherGridBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherGridTest {
  @get:Rule val compose = createComposeRule()
  private val bounds = LauncherGridBounds(1, 1, 4, 3, 4, 3)

  private fun grid(width: Int, height: Int): MutableState<Pair<Int, Int>> {
    val cells = mutableStateOf(width to height)
    compose.setContent {
      MaterialTheme {
        Box(Modifier.width(180.dp)) {
          LauncherGrid(
            bounds,
            cells.value.first,
            cells.value.second,
            { w, h -> cells.value = w to h },
            MaterialTheme.colorScheme.outlineVariant,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.primary,
          ) {
            Box(Modifier.fillMaxSize().testTag("grid-content"))
          }
        }
      }
    }
    return cells
  }

  @Test
  fun narrowViewportPreservesCaptureSizeAndExposesTheWholeHandle() {
    val cells = grid(4, 3)
    compose.onNodeWithTag("grid-content").assertWidthIsEqualTo(288.dp)
    compose.onNodeWithTag("grid-content").assertHeightIsEqualTo(252.dp)
    compose
      .onNodeWithTag("widget-grid-viewport")
      .performScrollToNode(hasTestTag("widget-resize-handle"))
    val handle = compose.onNodeWithTag("widget-resize-handle")
    handle.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
    val viewport = compose.onNodeWithTag("widget-grid-viewport").fetchSemanticsNode().boundsInRoot
    val target = handle.fetchSemanticsNode().boundsInRoot
    assertTrue(
      "The complete resize target must fit inside the scrollable viewport",
      target.left >= viewport.left - 1 &&
        target.top >= viewport.top - 1 &&
        target.right <= viewport.right + 1 &&
        target.bottom <= viewport.bottom + 1,
    )
    compose.runOnIdle { cells.value = 1 to 1 }
    compose.onNodeWithTag("grid-content").assertIsDisplayed().assertWidthIsEqualTo(72.dp)
    val pan =
      compose
        .onNodeWithTag("widget-grid-viewport")
        .fetchSemanticsNode()
        .config[SemanticsProperties.HorizontalScrollAxisRange]
    assertEquals("Compact must return into view after panning Roomy", 0f, pan.value(), 0.1f)
  }

  @Test
  fun accessibleResizeChangesTheContentRatherThanJustTheDescription() {
    val cells = mutableStateOf(1 to 1)
    compose.setContent {
      MaterialTheme {
        Box(Modifier.width(180.dp)) {
          LauncherGrid(
            bounds,
            cells.value.first,
            cells.value.second,
            { w, h -> cells.value = w to h },
            MaterialTheme.colorScheme.outlineVariant,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.primary,
          ) {
            Box(Modifier.fillMaxSize().testTag("grid-content"))
          }
        }
      }
    }
    val actions =
      compose
        .onNodeWithTag("widget-resize-handle")
        .fetchSemanticsNode()
        .config[SemanticsActions.CustomActions]
    compose.runOnIdle { assertTrue(actions.first { it.label == "Make wider" }.action()) }
    compose.onNodeWithTag("grid-content").assertWidthIsEqualTo(144.dp)
    compose
      .onNodeWithTag("widget-resize-handle")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "2 by 1 cells"))
    compose.onNodeWithTag("widget-resize-handle").performTouchInput {
      // A vertical drag remains available alongside horizontal viewport panning.
      swipe(center, center + Offset(0f, 110.dp.toPx()), durationMillis = 500)
    }
    compose.onNodeWithTag("grid-content").assertHeightIsEqualTo(168.dp)
  }
}
