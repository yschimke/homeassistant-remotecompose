@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ProvideHaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Same-document host updates must move the instrument, independently of its numeric text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeAssistantThemeRenderTest {
  @get:Rule val compose = createComposeRule()
  private var dark by mutableStateOf(false)
  private var theme by mutableStateOf(HaUberTheme.HomeAssistant)
  private var value by mutableStateOf(20f)
  private var target by mutableStateOf<Float?>(25f)
  private var captures = 0

  private fun snapshot(thermostat: Boolean): HaSnapshot {
    val id = if (thermostat) "climate.room" else "sensor.power"
    return HaSnapshot(
      states =
        mapOf(
          id to
            EntityState(
              id,
              if (thermostat) "heat" else value.toString(),
              buildJsonObject {
                put("friendly_name", if (thermostat) "Lounge thermostat" else "Power draw")
                if (thermostat) {
                  put("min_temp", 0)
                  put("max_temp", 100)
                  put("current_temperature", value)
                  target?.let { put("temperature", it) }
                  put("temperature_unit", "°C")
                } else {
                  put("unit_of_measurement", "W")
                }
              },
            )
        )
    )
  }

  private fun frame(name: String): Bitmap {
    compose.waitForIdle()
    val bitmap = compose.onNodeWithTag("card").captureToImage().asAndroidBitmap()
    assertTrue(bitmap.width <= 1800 && bitmap.height <= 1800)
    System.getProperty("lovelaceRenderEvidence")?.let { path ->
      File(path, "$name.png")
        .also { it.parentFile?.mkdirs() }
        .outputStream()
        .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    return bitmap
  }

  private fun verifyInstrument(thermostat: Boolean) {
    val json =
      if (thermostat) {
        """{"type":"thermostat","entity":"climate.room"}"""
      } else {
        """{"type":"gauge","entity":"sensor.power","min":0,"max":100}"""
      }
    val card = Json.decodeFromString(CardConfig.serializer(), json)
    // The production template path supplies host-reproducible dial bindings. The raw thermostat
    // converter deliberately uses dataSignature/re-encoding instead of those named fractions.
    val templates =
      listOf(HaUberTheme.Classic, HaUberTheme.HomeAssistant)
        .flatMap { style ->
          listOf(false, true).map { mode ->
            val key =
              assertNotNull(
                CardDesignTemplates.keyFor(
                  card,
                  snapshot(thermostat),
                  ThemeStyle.TerrazzoHome,
                  mode,
                  cardStyle = style,
                )
              )
            val built = CardDesignTemplates.capture(RuntimeEnvironment.getApplication(), key)
            captures++
            (style.id to mode) to CardDesignTemplates.playback(card, built.bytes)
          }
        }
        .toMap()
    compose.setContent {
      val currentTheme = theme
      val currentDark = dark
      val currentSnapshot = snapshot(thermostat)
      Box(
        Modifier.width(360.dp)
          .background(currentTheme.palette(currentDark).dashboardBackground)
          .testTag("card")
      ) {
        CachedCardPreview(
          cacheKey = "ha-theme-${currentTheme.id}-$currentDark-$thermostat",
          profile = androidXExperimentalWrap,
          card = card,
          snapshot = currentSnapshot,
          template = templates.getValue(currentTheme.id to currentDark),
        ) {
          captures++
          ProvideHaUberTheme(currentTheme, currentDark) {
            ProvideCardRegistry(defaultRegistry()) {
              RenderChild(card, currentSnapshot, RemoteModifier.fillMaxWidth())
            }
          }
        }
      }
    }
    for (mode in listOf(false, true)) {
      compose.runOnIdle {
        dark = mode
        theme = HaUberTheme.Classic
        value = 20f
        target = 25f
      }
      frame("ha-$thermostat-$mode-before-classic")
      compose.runOnIdle { theme = HaUberTheme.HomeAssistant }
      val low = frame("ha-$thermostat-$mode-after-low")
      val count = captures
      compose.runOnIdle { value = 80f }
      val high = frame("ha-$thermostat-$mode-after-high")
      assertEquals(count, captures, "Live readings must not recapture the document")
      assertEquals(low.width, high.width)
      assertEquals(low.height, high.height)
      // Top of each instrument excludes the central numeric text and all name/range labels.
      var changed = 0
      for (y in 16 until minOf(65, low.height)) for (x in 0 until low.width) {
        if (low.getPixel(x, y) != high.getPixel(x, y)) changed++
      }
      assertTrue(changed > 20, "The gauge arc / thermostat handle must move with host values")
      if (thermostat) {
        compose.runOnIdle { target = 85f }
        val moved = frame("ha-target-$mode-moved")
        assertEquals(count, captures, "A target-only update must not recapture")
        assertTrue(edgeChanges(high, moved) > 20, "The target handle must move independently")
        compose.runOnIdle { target = null }
        val absent = frame("ha-target-$mode-absent")
        assertEquals(count, captures, "A missing target must not recapture")
        assertTrue(edgeChanges(moved, absent) > 20, "A missing target must hide its handle")
      }
    }
  }

  /** Excludes central readings and labels, so changed text cannot hide a frozen target handle. */
  private fun edgeChanges(a: Bitmap, b: Bitmap): Int {
    var changed = 0
    for (y in 40 until minOf(230, a.height)) for (x in 0 until a.width) {
      if ((x < 130 || x >= 230) && a.getPixel(x, y) != b.getPixel(x, y)) changed++
    }
    return changed
  }

  @Test fun gaugeKeepsItsLiveArcInBothModes() = verifyInstrument(thermostat = false)

  @Test fun thermostatKeepsItsLiveDialAndTargetInBothModes() = verifyInstrument(thermostat = true)
}
