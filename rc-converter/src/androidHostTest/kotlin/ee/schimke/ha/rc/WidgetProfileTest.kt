package ee.schimke.ha.rc

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.Operations
import androidx.compose.remote.core.RemoteComposeBuffer
import ee.schimke.composeai.rccreation.compose.capture.RemoteCreationDisplayInfo
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetProfileTest {
  private fun emptyDocument(profile: CaptureProfile): ByteArray = runBlocking {
    captureRemoteDocument(RemoteCreationDisplayInfo(100, 100, 160), profile) {}
  }

  @Test
  fun widgetHeaderIsAcceptedByAndroid16WireVersion() {
    for (profile in listOf(widgetsProfile, systemThemedWidgetsProfile)) {
      val bytes = emptyDocument(profile)
      val document =
        CoreDocument().apply {
          initFromBuffer(
            RemoteComposeBuffer.fromInputStream(platformWidgetDocument(bytes).inputStream()),
          )
        }
      assertTrue(document.canBeDisplayed(1, 0, 0L))
    }
  }

  @Test
  fun launcherProfilesExcludeExperimentalFlowLayout() {
    for (profile in listOf(widgetsProfile, systemThemedWidgetsProfile)) {
      assertFalse(Operations.LAYOUT_FLOW in profile.supportedOperations)
    }
  }

  @Test
  fun android16Profile_excludesColorTheme() {
    assertFalse(Operations.COLOR_THEME in widgetsProfile.supportedOperations)
  }

  @Test
  fun systemThemedProfile_includesColorTheme() {
    assertTrue(Operations.COLOR_THEME in systemThemedWidgetsProfile.supportedOperations)
  }

  @Test
  fun onlyTheAndroid16DocumentDropsToWireVersion10() {
    // Header layout: the HEADER opcode, then the major and minor version as ints.
    fun minor(bytes: ByteArray) = java.nio.ByteBuffer.wrap(bytes, 5, 4).int
    val android16 = emptyDocument(widgetsProfile)
    assertTrue(minor(platformWidgetDocument(android16)) == 0)
    // An API 37 host reads 1.0 as document level 6, whose table has no ColorTheme (196).
    val themed =
      emptyDocument(systemThemedWidgetsProfile)
    assertTrue(minor(platformWidgetDocument(themed, android16Wire = false)) == 1)
  }
}
