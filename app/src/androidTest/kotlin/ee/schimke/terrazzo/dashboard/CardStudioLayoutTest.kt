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
            preview = {},
          )
        }
      }
    }
    val open = compose.onNodeWithText("Design in UI Builder")
    open.performClick()
    val error =
      compose.onNodeWithText(
        "The draft or connection settings are invalid. Check the HTTPS address and try again."
      )
    error.assertIsDisplayed()
    val agentHeading = compose.onNodeWithText("Connect an agent").fetchSemanticsNode().boundsInRoot
    assertTrue(
      "Server errors must appear near the server controls, before the agent form",
      error.fetchSemanticsNode().boundsInRoot.bottom <= agentHeading.top,
    )
    compose.onNodeWithText("Generate or fix draft").performScrollTo().assertIsDisplayed()
  }
}
