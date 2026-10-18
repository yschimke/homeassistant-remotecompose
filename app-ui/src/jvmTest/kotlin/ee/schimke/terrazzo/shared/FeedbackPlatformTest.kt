package ee.schimke.terrazzo.shared

import androidx.compose.ui.platform.UriHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackPlatformTest {
  @Test
  fun missingBrowserIsReportedWithoutThrowingOrExposingExceptionDetails() {
    val unavailable =
      object : UriHandler {
        override fun openUri(uri: String) {
          error("No browser installed")
        }
      }
    assertFalse(openFeedbackUrl(feedbackIssueUrl("1.0.0", "Desktop"), unavailable))
  }

  @Test
  fun opensExactlyTheReviewableDraftThroughTheHostUriHandler() {
    val opened = mutableListOf<String>()
    val handler =
      object : UriHandler {
        override fun openUri(uri: String) {
          opened.add(uri)
        }
      }
    val url = feedbackIssueUrl("1.0.0", "Desktop")
    assertTrue(openFeedbackUrl(url, handler))
    assertEquals(listOf(url), opened)
  }
}
