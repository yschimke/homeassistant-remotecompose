package ee.schimke.terrazzo.wear.widget

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.compose.capture.RemoteComposeCreationState
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.remote.material3.RemoteColorScheme
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A Wear widget's Remote Material 3 colours drive the HA roles its cards draw with. */
class WearHaThemeTest {

  @Test
  fun updatedRemoteMaterialColors_driveHaSemanticRoles() {
    val cardBackground = Color.Magenta.rc
    val updatedColors = RemoteColorScheme().copy(surfaceContainerHigh = cardBackground)

    assertSame(cardBackground, updatedColors.asHaTheme().cardBackground)
  }

  @Test
  fun wearMaterialTheme_surfaceContainer_encodesNamedRemoteColor() {
    val writer = RemoteComposeWriter.obtain(100, 100, RcPlatformProfiles.ANDROIDX)
    val creationState =
      RemoteComposeCreationState(
        RemoteCreationDisplayInfo(100, 100, 160),
        RcPlatformProfiles.ANDROIDX,
        writer,
      )

    RemoteColorScheme().asHaTheme().sectionBackground.writeToDocument(creationState)
    val document =
      CoreDocument().apply {
        initFromBuffer(
          RemoteComposeBuffer.fromInputStream(ByteArrayInputStream(writer.encodeToByteArray()))
        )
      }

    assertTrue(
      requireNotNull(document.namedColors).any { it?.endsWith("WearM3.surfaceContainer") == true },
      "Expected WearM3.surfaceContainer in ${document.namedColors.contentToString()}",
    )
  }
}
