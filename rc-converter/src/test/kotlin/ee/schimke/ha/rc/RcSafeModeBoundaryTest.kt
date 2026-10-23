package ee.schimke.ha.rc

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.terrazzo.shared.RcDisabledDocuments
import ee.schimke.terrazzo.shared.RcErrorBoundary
import ee.schimke.terrazzo.shared.RcSafeMode
import ee.schimke.terrazzo.shared.rcDocumentKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** In safe mode a document plays only after it has passed the player's runtime off-screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RcSafeModeBoundaryTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val good =
    RcDocumentCodec.encode(
      RcDocument(
        RcHeader(RcVersion(1, 0, 0)),
        listOf(RcNamedVariable(42, RcNamedVariable.STRING_TYPE, "USER:light.s0.state")),
      )
    )

  @BeforeTest fun safeMode() = run { RcSafeMode.active = true }

  @AfterTest
  fun cleanUp() {
    RcSafeMode.clear()
    RcDisabledDocuments.clear()
  }

  @Test
  fun aDocumentThatPassesPlaysOnceChecked() {
    compose.setContent { RcErrorBoundary(good) { BasicText("playing") } }
    compose.waitUntil(5_000) { RcSafeMode.passed(rcDocumentKey(good)) }
    compose.onNodeWithText("playing").assertExists()
  }

  @Test
  fun aDocumentThatFailsTheCheckIsDisabledWithoutPlaying() {
    val bad = byteArrayOf(1, 2, 3)
    var played = false
    compose.setContent {
      RcErrorBoundary(bad) {
        played = true
        BasicText("playing")
      }
    }
    compose.waitUntil(5_000) { RcDisabledDocuments.reason(rcDocumentKey(bad)) != null }
    assertNotNull(RcDisabledDocuments.reason(rcDocumentKey(bad)))
    compose.onNodeWithText("Card disabled after an error").assertExists()
    assertTrue(!played, "a document that fails the check never plays")
  }

  @Test
  fun outsideSafeModeADocumentPlaysAtOnce() {
    RcSafeMode.active = false
    compose.setContent { RcErrorBoundary(good) { BasicText("playing") } }
    compose.onNodeWithText("playing").assertExists()
  }
}
