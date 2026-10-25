package ee.schimke.terrazzo.shared

import ee.schimke.ha.model.*
import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.*

class PublishedInstrumentTemplateTest {
  @Test
  fun consoleInstrumentsOnlyPlayWithCompleteHostOwnedBindings() {
    val root =
      generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
    for ((type, id, attributes) in
      listOf(
        Triple(
          "gauge",
          "sensor.demo_power",
          """{"friendly_name":"Power draw","unit_of_measurement":"W"}""",
        ),
        Triple(
          "thermostat",
          "climate.demo_thermostat",
          """{"friendly_name":"Thermostat","temperature":22,"current_temperature":21.5,"min_temp":16,"max_temp":28,"hvac_action":"heating"}""",
        ),
      )) {
      val bytes = File(root, "scripts/fixtures/demo/$type-console.rc").readBytes()
      val card =
        CardConfig(
          type,
          buildJsonObject {
            put("type", type)
            put("entity", id)
          },
        )
      val snapshot =
        HaSnapshot(
          mapOf(
            id to
              EntityState(
                id,
                if (type == "gauge") "1100" else "heat",
                Json.parseToJsonElement(attributes).jsonObject,
              )
          )
        )
      assertTrue(canPlayWithCmp(bytes), "$type needs supported CMP operations")
      assertTrue(
        canBindWithCmp(bytes, templateBindings(card, snapshot)),
        "$type must have every declared live value",
      )
      // Asked for by key, the design binds the converter's own values.
      assertNotNull(exactDesignKey(card, snapshot, "console", dark = true), type)
      assertTrue(
        canBindWithCmp(bytes, exactTemplateBindings(card, snapshot)),
        "$type must bind every declared value from the converter",
      )
    }
  }

  @Test
  fun tileFixturesBindFromTheConverter() {
    // The demo browser test serves these for the lamp's key; the web app binds them by key.
    val root =
      generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
    val card =
      CardConfig(
        "tile",
        buildJsonObject {
          put("type", "tile")
          put("entity", "light.demo_lamp")
          put("name", "Demo lamp")
        },
      )
    val snapshot =
      HaSnapshot(
        mapOf(
          "light.demo_lamp" to
            EntityState(
              "light.demo_lamp",
              "off",
              Json.parseToJsonElement("""{"friendly_name":"Demo lamp"}""").jsonObject,
            )
        )
      )
    for (style in listOf("classic", "console")) {
      val bytes = File(root, "scripts/fixtures/demo/tile-$style.rc").readBytes()
      assertTrue(canBindWithCmp(bytes, exactTemplateBindings(card, snapshot)), "tile-$style")
    }
  }
}
