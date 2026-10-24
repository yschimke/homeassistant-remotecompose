package ee.schimke.ha.rc.components

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.compose.capture.RemoteComposeCreationState
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [ProvideNamedHaTheme] carries an HA palette as Remote Material 3's named `WearM3.<role>` colours,
 * which is what lets the preview server re-theme a published sticker. This pins that the names
 * reach the document.
 */
class NamedHaThemeTest {

  @Test
  fun namedTheme_writesHaRolesAsNamedWearM3Colors() {
    val writer = RemoteComposeWriter.obtain(100, 100, RcPlatformProfiles.ANDROIDX)
    val creationState =
      RemoteComposeCreationState(
        RemoteCreationDisplayInfo(100, 100, 160),
        RcPlatformProfiles.ANDROIDX,
        writer,
      )

    val haTheme = HaTheme.Light.asNamedRemote()
    haTheme.cardBackground.writeToDocument(creationState)
    haTheme.primaryText.writeToDocument(creationState)
    val document =
      CoreDocument().apply {
        initFromBuffer(
          RemoteComposeBuffer.fromInputStream(ByteArrayInputStream(writer.encodeToByteArray()))
        )
      }
    val names = requireNotNull(document.namedColors).filterNotNull()

    for (role in listOf("WearM3.surfaceContainerHigh", "WearM3.onSurface")) {
      assertTrue(names.any { it.endsWith(role) }, "Expected $role in $names")
    }
  }
}
