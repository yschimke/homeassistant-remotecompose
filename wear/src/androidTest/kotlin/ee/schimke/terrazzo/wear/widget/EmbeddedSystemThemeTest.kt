@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.wear.widget

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.core.operations.layout.managers.BoxLayout
import androidx.compose.remote.creation.Rc
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.modifiers.RecordingModifier
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import java.io.ByteArrayInputStream
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class EmbeddedSystemThemeTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun systemThemeColor_resolvesInEmbeddedComposePlayer() {
    val fallbackColor = 0xFFFF00FF.toInt()
    val writer = RemoteComposeWriter.obtain(100, 100, RcPlatformProfiles.ANDROIDX)
    val colorId =
      writer.addThemedColor(
        Rc.AndroidColors.GROUP,
        Rc.AndroidColors.SYSTEM_ACCENT2_50,
        Rc.AndroidColors.SYSTEM_ACCENT2_800,
        fallbackColor,
        fallbackColor,
      )
    writer.root {
      writer.box(
        RecordingModifier().backgroundId(colorId).fillMaxSize(),
        BoxLayout.CENTER,
        BoxLayout.CENTER,
      ) {}
    }
    val document =
      CoreDocument().apply {
        initFromBuffer(
          RemoteComposeBuffer.fromInputStream(ByteArrayInputStream(writer.encodeToByteArray()))
        )
      }

    composeRule.setContent {
      RemoteDocumentPlayer(
        document = document,
        documentWidth = 100,
        documentHeight = 100,
        modifier = Modifier,
      )
    }

    val image = composeRule.onRoot().captureToImage()
    val center = image.toPixelMap()[image.width / 2, image.height / 2].toArgb()
    assertNotEquals("embedded player fell back to magenta", fallbackColor, center)
  }
}
