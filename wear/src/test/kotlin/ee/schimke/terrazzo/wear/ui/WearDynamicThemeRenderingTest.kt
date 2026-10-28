package ee.schimke.terrazzo.wear.ui

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.dynamicColorScheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.wear.sync.WearOfflineStore
import ee.schimke.terrazzo.wear.sync.persistChanged
import ee.schimke.terrazzo.wear.widget.neutralTemplateFileName
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w240dp-h240dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearDynamicThemeRenderingTest {
  @get:Rule val compose = createComposeRule()
  private val context = RuntimeEnvironment.getApplication()

  @Test
  fun phoneSelectsWatchSystemColorsAndOfflineChangesRefreshTheRenderedDarkTheme() {
    fun systemColors(enabled: Boolean) {
      Settings.Global.putInt(
        context.contentResolver,
        "dynamic_color_theme_enabled",
        if (enabled) 1 else 0,
      )
      context.contentResolver.notifyChange(
        Settings.Global.getUriFor("dynamic_color_theme_enabled"),
        null,
      )
    }
    systemColors(true)
    val settings = WearSettings(themeStyle = "Material3", updatedAtMs = 100)
    WearOfflineStore(context).persistChanged(WearSyncPaths.SETTINGS, encodeProto(settings))
    var current by mutableStateOf(WearOfflineStore(context).readSettings()!!)
    var visible by mutableStateOf(true)
    compose.setContent {
      if (visible) {
        val look = current.wearCardLook()
        MaterialTheme(colorScheme = rememberWearColorScheme(look.palette)) {
          CompositionLocalProvider(LocalWearCardLook provides look) {
            Column(
              Modifier.size(240.dp)
                .background(MaterialTheme.colorScheme.background)
                .testTag("screen")
            ) {
              Button(onClick = {}) { Text("System colors") }
              WearRemoteCard(
                CardSummary(
                  type = "tile",
                  rawJson = """{"type":"tile","entity":"light.kitchen"}""",
                ),
                WearValues(
                  mapOf("light.kitchen" to EntityValue(state = "off", friendlyName = "Kitchen"))
                ),
              )
            }
          }
        }
      }
    }
    val dynamic = screenshot()
    val dynamicScheme = terrazzoWearColorScheme(ThemeStyle.Material3, context)
    assertEquals(assertNotNull(dynamicColorScheme(context)).primary, dynamicScheme.primary)
    assertTrue(
      dynamicScheme.background.luminance() < 0.1f,
      "Wear stays dark even with notnight configuration",
    )
    compose.runOnIdle { systemColors(false) }
    val fallback = screenshot()
    assertFalse(
      dynamic.sameAs(fallback),
      "Changing watch system colors must repaint without a phone packet",
    )
    val fallbackScheme = terrazzoWearColorScheme(ThemeStyle.Material3, context)
    assertNotEquals(
      neutralTemplateFileName(
        "tile",
        "off",
        wearPaletteKey(ThemeStyle.Material3, dynamicScheme),
        "classic",
      ),
      neutralTemplateFileName(
        "tile",
        "off",
        wearPaletteKey(ThemeStyle.Material3, fallbackScheme),
        "classic",
      ),
      "Captured fallback cards must not reuse a different system palette",
    )
    compose.runOnIdle { visible = false }
    compose.waitForIdle()
    compose.runOnIdle {
      current = WearOfflineStore(context).readSettings()!!
      visible = true
    }
    assertTrue(fallback.sameAs(screenshot()), "Offline restart retains the synced choice")
    compose.runOnIdle { systemColors(true) }
    assertTrue(dynamic.sameAs(screenshot()), "System colors still update after offline restart")
    compose.runOnIdle {
      WearOfflineStore(context)
        .persistChanged(
          WearSyncPaths.SETTINGS,
          encodeProto(settings.copy(themeStyle = "TerrazzoMushroom", updatedAtMs = 200)),
        )
      current = WearOfflineStore(context).readSettings()!!
    }
    val fixed = screenshot()
    assertFalse(dynamic.sameAs(fixed), "A new phone palette must replace system colors")
    compose.runOnIdle { systemColors(false) }
    assertTrue(fixed.sameAs(screenshot()), "Fixed mobile palettes ignore watch dynamic colors")
    save("watch-system-dynamic", dynamic)
    save("watch-system-fallback", fallback)
    save("watch-mobile-fixed", fixed)
  }

  @Test
  fun paletteKeysAreStableAcrossRecreationAndIncludeEveryColorRole() {
    val scheme = terrazzoWearColorScheme(ThemeStyle.Material3)
    val recreated = terrazzoWearColorScheme(ThemeStyle.Material3)
    assertEquals(
      wearPaletteKey(ThemeStyle.Material3, scheme),
      wearPaletteKey(ThemeStyle.Material3, recreated),
    )
    assertNotEquals(
      wearPaletteKey(ThemeStyle.Material3, scheme),
      wearPaletteKey(ThemeStyle.Material3, scheme.copy(onErrorContainer = Color.Green)),
    )
  }

  private fun screenshot(): Bitmap {
    compose.waitForIdle()
    return compose.onNodeWithTag("screen").captureToImage().asAndroidBitmap()
  }

  private fun save(name: String, bitmap: Bitmap) {
    System.getProperty("wearRenderEvidence")?.let { path ->
      val file = File(path, "$name.png").apply { parentFile?.mkdirs() }
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
  }
}
