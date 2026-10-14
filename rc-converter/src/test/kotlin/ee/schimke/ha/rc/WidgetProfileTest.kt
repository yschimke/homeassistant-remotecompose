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
  fun android16Profile_excludesColorTheme() {
    assertFalse(Operations.COLOR_THEME in widgetsProfile.supportedOperations)
  }

  @Test
  fun systemThemedProfile_includesColorTheme() {
    assertTrue(Operations.COLOR_THEME in systemThemedWidgetsProfile.supportedOperations)
  }
}
