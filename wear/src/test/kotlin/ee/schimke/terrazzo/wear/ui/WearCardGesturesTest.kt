package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A card's open gestures ([wearCardGestures]) around a button of its own, as the player draws one:
 * a Compose `clickable`, which consumes the press.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w240dp-h240dp-mdpi")
class WearCardGesturesTest {
  @get:Rule val compose = createComposeRule()

  private var clicks = 0
  private var longPresses = 0
  private var unhandled = 0

  private fun setCard() {
    compose.setContent {
      Box(
        Modifier.size(200.dp, 100.dp)
          .testTag("card")
          .wearCardGestures(onLongPress = { longPresses++ }, onUnhandledTap = { unhandled++ })
      ) {
        Box(
          Modifier.align(Alignment.CenterStart).size(60.dp).testTag("button").clickable { clicks++ }
        )
      }
    }
  }

  @Test
  fun aTapOnTheCardsButtonRunsItAndNothingElse() {
    setCard()
    compose.onNodeWithTag("button").performClick()
    compose.waitForIdle()
    assertEquals(listOf(1, 0, 0), listOf(clicks, longPresses, unhandled))
  }

  @Test
  fun aTapOutsideTheButtonIsUnhandled() {
    setCard()
    // The right end of the card: no button there.
    compose.onNodeWithTag("card").performTouchInput { click(Offset(right - 10f, centerY)) }
    compose.waitForIdle()
    assertEquals(listOf(0, 0, 1), listOf(clicks, longPresses, unhandled))
  }

  @Test
  fun aLongPressOnTheButtonOpensTheCardWithoutClickingIt() {
    setCard()
    compose.onNodeWithTag("button").performTouchInput { longClick() }
    compose.waitForIdle()
    assertEquals(listOf(0, 1, 0), listOf(clicks, longPresses, unhandled))
  }

  @Test
  fun aLongPressOutsideTheButtonOpensTheCard() {
    setCard()
    compose.onNodeWithTag("card").performTouchInput { longClick(Offset(right - 10f, centerY)) }
    compose.waitForIdle()
    assertEquals(listOf(0, 1, 0), listOf(clicks, longPresses, unhandled))
  }
}
