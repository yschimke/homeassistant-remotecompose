package ee.schimke.terrazzo.wear.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.client.design.cardFingerprint
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.wear.sync.WearOfflineStore
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.WearDesignBlob
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import ee.schimke.terrazzo.wearsync.proto.WearSlotDesign
import ee.schimke.terrazzo.wearsync.proto.WearSlotDesigns
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w240dp-h240dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearThemeModeRenderingTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun systemUsesWatchConfigurationAndBothCachedDesignsWhileExplicitModesWin() {
    val context = RuntimeEnvironment.getApplication()
    val store = WearOfflineStore(context)
    val raw = """{"type":"tile","entity":"light.kitchen"}"""
    val card = requireNotNull(decodeWearCard(raw))
    val values = mapOf("light.kitchen" to EntityValue(state = "off", friendlyName = "Kitchen"))
    val snapshot = wearSnapshot(values)
    val designs =
      listOf(true, false).flatMap { dark ->
        val key =
          requireNotNull(
            CardDesignTemplates.keyFor(card, snapshot, ThemeStyle.TerrazzoMushroom, dark)
          )
        val bytes = CardDesignTemplates.capture(context, key).bytes
        val sha = Sha256.hex(bytes)
        store.writeDesign(WearDesignBlob(sha, bytes))
        DesignKey.WEAR_CONTAINERS.map { WearSlotDesign(cardFingerprint(card), it, sha, dark) }
      }
    store.writeSlotDesigns(WearSlotDesigns(designs, 1L))
    store.writeSettings(
      WearSettings(themeStyle = "TerrazzoMushroom", darkMode = "Follow", updatedAtMs = 1)
    )
    var settings by mutableStateOf(store.readSettings()!!)
    var watchNight by mutableStateOf(false)
    var visible by mutableStateOf(true)
    compose.setContent {
      val config =
        Configuration(context.resources.configuration).apply {
          uiMode =
            (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
              if (watchNight) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
      CompositionLocalProvider(LocalConfiguration provides config) {
        val look = settings.wearCardLook(isSystemInDarkTheme())
        MaterialTheme(colorScheme = terrazzoWearColorScheme(look.palette, look.dark)) {
          CompositionLocalProvider(LocalWearCardLook provides look) {
            Box(
              Modifier.size(240.dp)
                .background(MaterialTheme.colorScheme.background)
                .testTag("screen")
            ) {
              if (visible) WearRemoteCard(CardSummary(type = "tile", rawJson = raw), values)
            }
          }
        }
      }
    }
    val light = screenshot()
    compose.runOnIdle { watchNight = true }
    val dark = screenshot()
    assertFalse(
      light.sameAs(dark),
      "System must use the watch configuration for chrome and selected template",
    )
    fun receive(mode: String) {
      compose.runOnIdle {
        store.writeSettings(settings.copy(darkMode = mode, updatedAtMs = settings.updatedAtMs + 1))
        settings = WearOfflineStore(context).readSettings()!!
      }
    }
    receive("Light")
    assertTrue(light.sameAs(screenshot()), "Explicit Light must override a dark watch")
    compose.runOnIdle { watchNight = false }
    assertTrue(light.sameAs(screenshot()), "Explicit Light must ignore watch system changes")
    receive("Dark")
    assertTrue(dark.sameAs(screenshot()), "Explicit Dark must override a light watch")
    receive("Follow")
    compose.runOnIdle { visible = false }
    compose.waitForIdle()
    compose.runOnIdle {
      settings = WearOfflineStore(context).readSettings()!!
      visible = true
    }
    assertTrue(
      light.sameAs(screenshot()),
      "Offline recreation must retain System mode and the light template",
    )
    compose.runOnIdle { watchNight = true }
    assertTrue(
      dark.sameAs(screenshot()),
      "A watch system change must work offline without a new phone payload",
    )
    save("system-watch-light", light)
    save("system-watch-dark", dark)
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
