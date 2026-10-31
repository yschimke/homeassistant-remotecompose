package ee.schimke.ha.rc

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.Operations
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.creation.RemoteComposeWriter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetProfileTest {
  @Test
  fun widgetHeaderIsAcceptedByAndroid16WireVersion() {
    for (profile in listOf(widgetsProfile, systemThemedWidgetsProfile)) {
      val bytes = RemoteComposeWriter.obtain(100, 100, profile).encodeToByteArray()
      val document =
        CoreDocument().apply {
          initFromBuffer(
            RemoteComposeBuffer.fromInputStream(platformWidgetDocument(bytes).inputStream())
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
    val android16 = RemoteComposeWriter.obtain(100, 100, widgetsProfile).encodeToByteArray()
    assertTrue(minor(platformWidgetDocument(android16)) == 0)
    // An API 37 host reads 1.0 as document level 6, whose table has no ColorTheme (196).
    val themed =
      RemoteComposeWriter.obtain(100, 100, systemThemedWidgetsProfile).encodeToByteArray()
    assertTrue(minor(platformWidgetDocument(themed, android16Wire = false)) == 1)
  }
}
