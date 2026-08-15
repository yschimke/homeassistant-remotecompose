package ee.schimke.ha.rc.components

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.creation.Rc
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.compose.capture.RemoteComposeCreationState
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.wear.compose.remote.material3.RemoteColorScheme
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SystemRemoteHaThemeTest {

  @Test
  fun updatedRemoteMaterialColors_driveHaSemanticRoles() {
    val cardBackground = Color.Magenta.rc
    val updatedColors = RemoteColorScheme().copy(surfaceContainerHigh = cardBackground)

    assertSame(cardBackground, updatedColors.asHaTheme().cardBackground)
  }

  @Test
  fun cardBackground_encodesAndroidLightAndDarkResources() {
    val writer = RemoteComposeWriter.obtain(100, 100, RcPlatformProfiles.ANDROIDX)
    val creationState =
      RemoteComposeCreationState(
        RemoteCreationDisplayInfo(100, 100, 160),
        RcPlatformProfiles.ANDROIDX,
        writer,
      )

    val colorId =
      SystemRemoteHaTheme.asColorScheme().surfaceContainerHigh.writeToDocument(creationState)
    val document =
      CoreDocument().apply {
        initFromBuffer(
          RemoteComposeBuffer.fromInputStream(ByteArrayInputStream(writer.encodeToByteArray()))
        )
      }
    val color = requireNotNull(document.themedColors).single()

    assertNotEquals(0, colorId)
    assertEquals(Rc.AndroidColors.SYSTEM_SURFACE_CONTAINER_HIGH_LIGHT, color.mLightModeIndex)
    assertEquals(Rc.AndroidColors.SYSTEM_SURFACE_CONTAINER_HIGH_DARK, color.mDarkModeIndex)
    assertEquals(HaTheme.Light.cardBackground.toArgb(), color.mLightModeFallback)
    assertEquals(HaTheme.Dark.cardBackground.toArgb(), color.mDarkModeFallback)
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

    RemoteColorScheme().surfaceContainer.writeToDocument(creationState)
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
