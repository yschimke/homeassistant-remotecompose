package ee.schimke.terrazzo.shared

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportFeedbackTest {
  @Test
  fun draftIsAnEditableBrowserFlowWithOnlySafeContext() {
    val url = Url(feedbackIssueUrl("0.1.43", "Web (Wasm)"))
    assertEquals("https", url.protocol.name)
    assertEquals("github.com", url.host)
    assertEquals("/yschimke/homeassistant-remotecompose/issues/new", url.encodedPath)
    assertEquals(setOf("body"), url.parameters.names())
    val body = url.parameters["body"]!!
    assertTrue(body.contains("App version: 0.1.43"))
    assertTrue(body.contains("Platform: Web (Wasm)"))
    listOf(
        "Steps to reproduce (optional)",
        "Expected behavior",
        "Actual behavior",
        "PUBLIC",
        "credentials",
      )
      .forEach { assertTrue(body.contains(it), it) }
    assertFalse(body.contains("access_token="))
    assertFalse(body.contains("demo://"))
    assertFalse(body.contains("http://"))
    assertFalse(body.contains("https://"))
  }

  @Test
  fun contextCannotCreateAdditionalQueryParameters() {
    val version = "dev + &token=secret#fragment\nUnicode: ü"
    val platform = "Desktop / test?"
    val url = Url(feedbackIssueUrl(version, platform))
    assertEquals(setOf("body"), url.parameters.names())
    assertEquals("", url.fragment)
    assertTrue(url.parameters["body"]!!.contains("App version: $version"))
    assertTrue(url.parameters["body"]!!.contains("Platform: $platform"))
  }
}
