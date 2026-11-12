package ee.schimke.ha.rc.components

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.creation.Rc
import androidx.compose.ui.graphics.toArgb
import ee.schimke.composeai.rccreation.compose.capture.RemoteCreationDisplayInfo
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.ha.rc.androidX
import ee.schimke.ha.rc.captureRemoteDocument
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemRemoteHaThemeTest {

  @Test
  fun cardBackground_encodesAndroidLightAndDarkResources() {
    val bytes = runBlocking {
      captureRemoteDocument(RemoteCreationDisplayInfo(100, 100, 160), androidX) {
        RemoteBox(RemoteModifier.fillMaxSize().background(SystemRemoteHaTheme.cardBackground))
      }
    }
    val document =
      CoreDocument().apply {
        initFromBuffer(RemoteComposeBuffer.fromInputStream(ByteArrayInputStream(bytes)))
      }
    val color = requireNotNull(document.themedColors).single()

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
