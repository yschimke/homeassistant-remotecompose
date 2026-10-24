@file:Suppress("RestrictedApi", "COMPOSE_APPLIER_CALL_MISMATCH")

package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.*
import java.io.File
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual converter bytes for the dashboard render port, not an imitation of card visuals. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LovelaceDashboardRenderTest {
  private val states =
    mapOf(
      "light.sofa" to
        EntityState(
          "light.sofa",
          "on",
          buildJsonObject { put("friendly_name", "Sofa") },
        ),
      "sensor.room" to
        EntityState(
          "sensor.room",
          "21.5",
          buildJsonObject {
            put("friendly_name", "Room")
            put("device_class", "temperature")
            put("unit_of_measurement", "°C")
          },
        ),
      "climate.boiler" to
        EntityState(
          "climate.boiler",
          "heat",
          buildJsonObject {
            put("friendly_name", "Boiler")
            put("current_temperature", 21.5)
            put("temperature", 22)
          },
        ),
    )

  private suspend fun capture(
    name: String,
    json: String,
    style: HaUberTheme = HaUberTheme.Classic,
    dark: Boolean = false,
    entities: Map<String, EntityState> = states,
  ): ByteArray {
    val card = Json.decodeFromString(CardConfig.serializer(), json)
    val snapshot = HaSnapshot(states = entities)
    val palette = style.cardPalette(ThemeStyle.TerrazzoHome, dark)
    val result =
      captureSingleRemoteDocument(
          RuntimeEnvironment.getApplication(),
          RemoteCreationDisplayInfo(360, 192, 160),
          profile = androidXExperimental,
        ) {
          ProvideCardRegistry(defaultRegistry()) {
            ProvideHaTheme(palette) {
              ProvideCardStyle(style, palette, dark) {
                RenderChild(card, snapshot, RemoteModifier.fillMaxWidth())
              }
            }
          }
        }
        .bytes
    assertTrue(result.isNotEmpty())
    CardDocument(result, 360, 192).decode()
    assertEquals(
      Json.parseToJsonElement(json),
      Json.encodeToJsonElement(CardConfig.serializer(), card),
    )
    System.getProperty("lovelaceRenderEvidence")?.let {
      File(it, "$name.rc").apply {
        parentFile.mkdirs()
        writeBytes(result)
      }
    }
    return result
  }

  @Test
  fun realCardOutputRespondsToHostValuesStylesAndUnsupportedConfigs() = runTest {
    val tile =
      """{"type":"tile","entity":"light.sofa","name":"Sofa","grid_options":{"columns":6,"rows":2}}"""
    val on = capture("tile-on", tile)
    assertContentEquals(
      on,
      capture("tile-on-repeat", tile),
      "Identical inputs must be stable before comparing live/style changes",
    )
    assertTrue("Sofa" in String(on, Charsets.ISO_8859_1))
    val off =
      capture(
        "tile-off",
        tile,
        entities = states + ("light.sofa" to states.getValue("light.sofa").copy(state = "off")),
      )
    assertFalse(on.contentEquals(off), "Live state must affect actual converter output")
    val styled = capture("tile-braun-dark", tile, HaUberTheme.Braun, dark = true)
    assertFalse(
      on.contentEquals(styled),
      "Host style and light/dark mode must affect actual converter output",
    )
    capture("gauge", """{"type":"gauge","entity":"sensor.room","min":0,"max":40}""")
    capture(
      "custom",
      """{"type":"custom:mushroom-template-card","entity":"sensor.room","unknown":{"preserve":true}}""",
    )
    capture("climate", """{"type":"tile","entity":"climate.boiler"}""")
  }
}
