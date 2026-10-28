package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.shared.CardStudioDefaults
import ee.schimke.terrazzo.shared.CardStudioMode
import ee.schimke.terrazzo.shared.CardStudioScreen
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardStudioLayoutTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun connectionErrorAppearsBesideTheActionOnANarrowScreen() {
    compose.setContent {
      MaterialTheme {
        Box(Modifier.width(280.dp)) {
          CardStudioScreen(
            CardConfig("tile", buildJsonObject {}),
            HaSnapshot(),
            {},
            initialServerUrl = "http://invalid.example",
            defaults = CardStudioDefaults(mode = CardStudioMode.Builder),
            preview = {},
          )
        }
      }
    }
    val open = compose.onNodeWithText("Open in UI Builder")
    open.performScrollTo().performClick()
    val error =
      compose.onNodeWithText(
        "The draft or connection settings are invalid. Check the HTTPS address and try again."
      )
    error.assertIsDisplayed()
    val options = compose.onNodeWithText("More options").fetchSemanticsNode().boundsInRoot
    assertTrue(
      "Server errors must appear in the step that failed, before the options below it",
      error.fetchSemanticsNode().boundsInRoot.bottom <= options.top,
    )
    // The remembered choice folds its step, and Change reopens it.
    compose.onNodeWithText("Change").performScrollTo().performClick()
    compose.onNodeWithText("With an AI agent").performScrollTo().assertIsDisplayed()
  }
}
