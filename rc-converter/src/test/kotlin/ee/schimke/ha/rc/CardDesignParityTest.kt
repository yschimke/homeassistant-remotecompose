package ee.schimke.ha.rc

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual parity for the design path: a tile rendered from its local-library template, with slot
 * bindings pushed, must draw the same pixels as the same tile captured directly through its
 * converter. This is what makes "server first, library fallback" safe as the one rendering path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardDesignParityTest {

  @get:Rule val compose = createComposeRule()

  private val registry = defaultRegistry()
  private val style = ThemeStyle.TerrazzoHome

  private fun tile(entity: String, name: String? = null) =
    CardConfig(
      type = "tile",
      raw =
        buildJsonObject {
          put("type", "tile")
          put("entity", entity)
          name?.let { put("name", it) }
        },
    )

  private fun entity(id: String, state: String, vararg attrs: Pair<String, String>) =
    EntityState(id, state, JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) }))

  private fun assertParity(name: String, card: CardConfig, state: EntityState, dark: Boolean) {
    val snapshot = HaSnapshot(states = mapOf(state.entityId to state))
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val key = assertNotNull(CardDesignTemplates.keyFor(card, snapshot, style, dark, registry))
    val playback =
      CardDesignTemplates.playback(card, CardDesignLibrary.template(context, key, registry))
    val theme = haThemeFor(style, dark)

    var useTemplate by mutableStateOf(false)
    compose.setContent {
      Box(Modifier.width(360.dp).testTag("card")) {
        if (!useTemplate) {
          CachedCardPreview(
            cacheKey = "direct-$name",
            profile = androidXExperimentalWrap,
            card = card,
            snapshot = snapshot,
          ) {
            ProvideCardRegistry(registry) {
              ProvideHaTheme(theme) { RenderChild(card, snapshot, RemoteModifier.fillMaxWidth()) }
            }
          }
        } else {
          CachedCardPreview(
            cacheKey = "template-$name",
            profile = androidXExperimentalWrap,
            card = card,
            snapshot = snapshot,
            template = playback,
          ) {}
        }
      }
    }
    compose.waitForIdle()
    val direct = capture("direct-$name")
    compose.runOnIdle { useTemplate = true }
    compose.waitForIdle()
    val templated = capture("template-$name")

    assertEquals(direct.width, templated.width, "$name width")
    assertEquals(direct.height, templated.height, "$name height")
    var differing = 0
    val colors = mutableSetOf<androidx.compose.ui.graphics.Color>()
    for (y in 0 until direct.height) for (x in 0 until direct.width) {
      colors += direct[x, y]
      if (direct[x, y] != templated[x, y]) differing++
    }
    assertTrue(colors.size > 2, "$name: the direct capture must draw real content")
    assertEquals(0, differing, "$name: pixels differing between direct and template render")
  }

  private fun capture(name: String) =
    compose
      .onNodeWithTag("card")
      .captureToImage()
      .also { image ->
        val dir = File("build/card-design-parity").also { it.mkdirs() }
        dir.resolve("$name.png").outputStream().use {
          image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
      }
      .toPixelMap()

  @Test
  fun lightOn() =
    assertParity(
      "light-on",
      tile("light.kitchen", "Kitchen"),
      entity("light.kitchen", "on"),
      dark = false,
    )

  @Test
  fun lightOffDark() =
    assertParity(
      "light-off-dark",
      tile("light.hall"),
      entity("light.hall", "off", "friendly_name" to "Hall"),
      dark = true,
    )

  @Test
  fun switchOn() =
    assertParity(
      "switch-on",
      tile("switch.fan"),
      entity("switch.fan", "on", "friendly_name" to "Fan"),
      dark = false,
    )

  @Test
  fun temperatureSensor() =
    assertParity(
      "sensor-temperature",
      tile("sensor.temp"),
      entity(
        "sensor.temp",
        "21.5",
        "friendly_name" to "Living room",
        "device_class" to "temperature",
        "unit_of_measurement" to "°C",
      ),
      dark = false,
    )

  @Test
  fun doorOpen() =
    assertParity(
      "binary-sensor-door",
      tile("binary_sensor.front_door"),
      entity(
        "binary_sensor.front_door",
        "on",
        "friendly_name" to "Front door",
        "device_class" to "door",
      ),
      dark = false,
    )
}
