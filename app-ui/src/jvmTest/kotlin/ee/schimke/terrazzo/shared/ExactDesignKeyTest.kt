package ee.schimke.terrazzo.shared

import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.designs.LibraryCaptureProfile
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CommonCaptureHost
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The web app asks the design server for a card's design by its exact key, and plays what comes
 * back with its own bindings: the key must be the phone app's, carry no values, and the design the
 * server captures for it must bind completely from the host.
 */
class ExactDesignKeyTest {
  private val host = CommonCaptureHost(LibraryCaptureProfile.OPERATIONS)

  private fun card(json: String) =
    Json.parseToJsonElement(json).jsonObject.let { CardConfig(it["type"].toString().trim('"'), it) }

  private fun state(id: String, state: String, attributes: String = "{}") =
    id to EntityState(id, state, Json.parseToJsonElement(attributes) as JsonObject)

  private val cases =
    listOf(
      card("""{"type":"tile","entity":"light.kitchen","name":"Kitchen"}""") to
        HaSnapshot(mapOf(state("light.kitchen", "on", """{"friendly_name":"Kitchen"}"""))),
      card("""{"type":"entity","entity":"sensor.outside"}""") to
        HaSnapshot(
          mapOf(
            state(
              "sensor.outside",
              "12.5",
              """{"friendly_name":"Outside","unit_of_measurement":"°C","device_class":"temperature"}""",
            )
          )
        ),
      card("""{"type":"gauge","entity":"sensor.power","min":0,"max":3000}""") to
        HaSnapshot(
          mapOf(
            state("sensor.power", "1100", """{"friendly_name":"Power","unit_of_measurement":"W"}""")
          )
        ),
    )

  @Test
  fun theKeyIsThePhoneAppsAndCarriesNoValues() {
    for ((card, snapshot) in cases) {
      val key = assertNotNull(exactDesignKey(card, snapshot, "classic", dark = true), card.type)
      assertEquals(
        CardDesignTemplates.keyFor(card, snapshot, ThemeStyle.entries.first(), true),
        key,
      )
      assertEquals(DesignSurface.MobileApp, key.surface)
      val wire = key.toString()
      for (value in listOf("kitchen", "Kitchen", "outside", "12.5", "1100", "power")) {
        assertFalse(value in wire, "${card.type}'s key must not carry \"$value\": $wire")
      }
    }
  }

  @Test
  fun aCardStyleIsPartOfTheKey() {
    val (card, snapshot) = cases.first()
    val styled = HaUberTheme.all.first { it.id != HaUberTheme.Classic.id }
    assertEquals(
      CardDesignTemplates.keyFor(
        card,
        snapshot,
        ThemeStyle.entries.first(),
        false,
        cardStyle = styled,
      ),
      exactDesignKey(card, snapshot, styled.id, dark = false),
    )
  }

  @Test
  fun theServersDesignForTheKeyBindsFromTheHost() {
    for ((card, snapshot) in cases) {
      val key = assertNotNull(exactDesignKey(card, snapshot, "classic", dark = false))
      val bytes = CardDesignTemplates.capture(host, key).bytes
      assertTrue(canPlayWithCmp(bytes), "${card.type} plays in the web player")
      assertTrue(
        canBindWithCmp(bytes, exactTemplateBindings(card, snapshot)),
        "${card.type}'s design binds every value it declares",
      )
    }
  }
}
