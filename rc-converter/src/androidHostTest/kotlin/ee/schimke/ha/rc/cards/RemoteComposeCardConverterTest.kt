package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.REMOTE_COMPOSE_CARD_TYPE
import ee.schimke.ha.model.remoteComposeCardStyle
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.SystemHaClock
import ee.schimke.ha.rc.components.ThemeStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class RemoteComposeCardConverterTest {
  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          "sensor.nozzle" to
            EntityState(
              "sensor.nozzle",
              "220",
              Json.parseToJsonElement(
                  """{"device_class":"temperature","unit_of_measurement":"°C"}"""
                )
                .jsonObject,
            ),
          "binary_sensor.online" to EntityState("binary_sensor.online", "on"),
        )
    )

  private fun card(json: String): CardConfig {
    val raw = Json.parseToJsonElement(json).jsonObject
    return CardConfig(raw["type"].toString().trim('"'), raw)
  }

  private val inner = card("""{"type":"tile","entity":"sensor.nozzle","name":"Nozzle"}""")
  private val wrapper =
    card(
      """{"type":"$REMOTE_COMPOSE_CARD_TYPE","card_style":"braun",
         "card":{"type":"tile","entity":"sensor.nozzle","name":"Nozzle"}}"""
    )

  @Test
  fun drawsAsItsInnerCardsDesign() {
    assertEquals(inner, CardDesignTemplates.designCard(wrapper, snapshot))
    val key = CardDesignTemplates.keyFor(wrapper, snapshot, ThemeStyle.entries.first(), false)
    assertNotNull(key)
    assertEquals(
      CardDesignTemplates.keyFor(inner, snapshot, ThemeStyle.entries.first(), false),
      key,
    )
  }

  @Test
  fun bindsTheInnerCardsLiveValues() {
    val bytes = ByteArray(0)
    val design = CardDesignTemplates.designCard(wrapper, snapshot)
    assertEquals(
      CardDesignTemplates.playback(
          CardDesignTemplates.designCard(inner, snapshot),
          bytes,
        )
        .bindings(snapshot, SystemHaClock),
      CardDesignTemplates.playback(design, bytes).bindings(snapshot, SystemHaClock),
    )
  }

  @Test
  fun namesItsCardStyle() {
    assertEquals("braun", remoteComposeCardStyle(wrapper))
    assertNull(remoteComposeCardStyle(inner))
  }

  @Test
  fun withoutAnInnerCardItHasNoDesign() {
    val empty = card("""{"type":"$REMOTE_COMPOSE_CARD_TYPE"}""")
    assertNull(CardDesignTemplates.keyFor(empty, snapshot, ThemeStyle.entries.first(), false))
  }

  @Test
  fun aConditionalInsideTheWrapperStillReencodesWhenItFlips() {
    val conditional =
      card(
        """{"type":"$REMOTE_COMPOSE_CARD_TYPE","card":{"type":"conditional",
           "conditions":[{"entity":"binary_sensor.online","state":"on"}],
           "card":{"type":"tile","entity":"sensor.nozzle"}}}"""
      )
    val off =
      snapshot.copy(
        states =
          snapshot.states + ("binary_sensor.online" to EntityState("binary_sensor.online", "off"))
      )
    assertEquals("1", cardVisibilitySignature(conditional, snapshot))
    assertEquals("0", cardVisibilitySignature(conditional, off))
  }
}
