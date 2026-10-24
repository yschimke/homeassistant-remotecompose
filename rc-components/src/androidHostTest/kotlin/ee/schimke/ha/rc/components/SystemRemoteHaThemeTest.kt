package ee.schimke.ha.rc.components

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.creation.Rc
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.compose.capture.RemoteComposeCreationState
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.ui.graphics.toArgb
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SystemRemoteHaThemeTest {

  @Test
  fun cardBackground_encodesAndroidLightAndDarkResources() {
    val writer = RemoteComposeWriter.obtain(100, 100, RcPlatformProfiles.ANDROIDX)
    val creationState =
      RemoteComposeCreationState(
        RemoteCreationDisplayInfo(100, 100, 160),
        RcPlatformProfiles.ANDROIDX,
        writer,
      )

    val colorId = SystemRemoteHaTheme.cardBackground.writeToDocument(creationState)
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

  /**
   * The regression the original implementation shipped, and the cheapest possible guard against it.
   *
   * `BackgroundModifier` branches on `hasConstantValue`: a colour that carries one is written as
   * literal red/green/blue by `SolidBackgroundModifier` and its id provider is never asked for. So
   * a themed colour built as `RemoteColor(lightFallback)` with an overridden `writeToDocument` —
   * which is how this started — emits **no** `ColorTheme` operation through any composable path,
   * while rendering correctly, because the constant it falls back to is the light fallback.
   *
   * The other tests in this file call `writeToDocument` directly, so they pass either way. This one
   * asserts the property the modifier actually reads.
   */
  @Test
  fun systemThemeColors_carryNoConstantValue_soTheIdPathIsTaken() {
    val theme = SystemRemoteHaTheme
    for ((role, color) in
      listOf(
        "cardBackground" to theme.cardBackground,
        "primaryText" to theme.primaryText,
        "placeholderAccent" to theme.placeholderAccent,
      )) {
      assertNull(
        color.constantValueOrNull,
        "$role carries a constant value, so BackgroundModifier will bake it and skip the " +
          "ColorTheme operation",
      )
    }
  }
}
