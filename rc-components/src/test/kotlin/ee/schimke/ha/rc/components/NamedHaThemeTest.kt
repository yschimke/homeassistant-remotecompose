package ee.schimke.ha.rc.components

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.compose.capture.RemoteComposeCreationState
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.wear.compose.remote.material3.RemoteColorScheme
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ProvideNamedHaTheme] carries an HA palette as Remote Material 3's named `WearM3.<role>` colours,
 * which is what lets the preview server re-theme a published sticker. These pin the role mapping
 * against [asHaTheme], which components read, and that the names reach the document.
 */
class NamedHaThemeTest {

  @Test
  fun wearColorScheme_putsEachHaRoleInTheSlotComponentsReadItFrom() {
    for (theme in listOf(HaTheme.Light, HaTheme.Dark)) {
      val scheme = theme.asWearColorScheme()
      assertEquals(theme.cardBackground, scheme.surfaceContainerHigh)
      assertEquals(theme.dashboardBackground, scheme.background)
      assertEquals(theme.sectionBackground, scheme.surfaceContainer)
      assertEquals(theme.primaryText, scheme.onSurface)
      assertEquals(theme.secondaryText, scheme.onSurfaceVariant)
      assertEquals(theme.linkText, scheme.tertiary)
      assertEquals(theme.divider, scheme.outlineVariant)
      assertEquals(theme.placeholderAccent, scheme.primary)
      assertEquals(theme.placeholderBackground, scheme.primaryContainer)
      assertEquals(theme.unknownAccent, scheme.secondary)
    }
  }

  @Test
  fun namedTheme_writesHaRolesAsNamedWearM3Colors() {
    val writer = RemoteComposeWriter.obtain(100, 100, RcPlatformProfiles.ANDROIDX)
    val creationState =
      RemoteComposeCreationState(
        RemoteCreationDisplayInfo(100, 100, 160),
        RcPlatformProfiles.ANDROIDX,
        writer,
      )

    val haTheme = RemoteColorScheme(HaTheme.Light.asWearColorScheme()).asHaTheme()
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
