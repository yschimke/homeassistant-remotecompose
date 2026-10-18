package ee.schimke.terrazzo

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ee.schimke.terrazzo.shared.PUBLIC_FEEDBACK_WARNING
import ee.schimke.terrazzo.shared.PUBLIC_ISSUE_TRACKER
import ee.schimke.terrazzo.shared.ReportFeedbackButton
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportFeedbackTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun warningAndCancellationPrecedeAnyBrowserHandoff() {
    val opened = mutableListOf<String>()
    show { opened.add(it) }
    compose.onNodeWithText("Report a bug").performClick()
    compose.onNodeWithText(PUBLIC_FEEDBACK_WARNING).assertIsDisplayed()
    assertTrue(opened.isEmpty())
    compose.onNodeWithText("Cancel").performClick()
    assertTrue(opened.isEmpty())
    compose.onNodeWithText("Report a bug").performClick()
    compose.onNodeWithText("Review on GitHub").performClick()
    assertEquals(1, opened.size)
    assertTrue(opened.single().startsWith("$PUBLIC_ISSUE_TRACKER/new?body="))
  }

  @Test
  fun missingBrowserKeepsPublicWarningAndManualRecoveryVisible() {
    show { error("No activity can handle the URL") }
    compose.onNodeWithText("Report a bug").performClick()
    compose.onNodeWithText("Review on GitHub").performClick()
    compose.onNodeWithText("Report a PUBLIC issue").assertIsDisplayed()
    compose.onNodeWithText(PUBLIC_ISSUE_TRACKER).assertIsDisplayed()
    compose.onNodeWithText("Try GitHub again").assertIsDisplayed()
  }

  private fun show(open: (String) -> Unit) {
    compose.setContent {
      CompositionLocalProvider(
        LocalUriHandler provides
          object : UriHandler {
            override fun openUri(uri: String) = open(uri)
          }
      ) {
        MaterialTheme { ReportFeedbackButton(version = "1.2.3") }
      }
    }
  }
}
