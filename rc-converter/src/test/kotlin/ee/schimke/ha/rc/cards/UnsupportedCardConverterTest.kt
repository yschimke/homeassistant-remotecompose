package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UnsupportedCardConverterTest {
  private val snapshot =
    HaSnapshot(
      states =
        listOf("light.kitchen", "sensor.temp", "cover.garage", "sun.sun").associateWith {
          EntityState(it, "on")
        }
    )

  private fun card(json: String): CardConfig {
    val raw = Json.parseToJsonElement(json).jsonObject
    return CardConfig(type = raw["type"]!!.jsonPrimitive.content, raw = raw)
  }

  private fun ids(generic: CardConfig): List<String> =
    (generic.raw["entities"] as JsonArray).map {
      when (it) {
        is JsonPrimitive -> it.content
        else -> (it as JsonObject)["entity"]!!.jsonPrimitive.content
      }
    }

  @Test
  fun aSingleEntityCustomCardKeepsItsNameOnTheRow() {
    val generic =
      genericCard(
        card("""{"type":"custom:mushroom-light-card","entity":"light.kitchen","name":"Kitchen"}""")
      )!!
    assertEquals("entities", generic.type)
    assertNull(generic.raw["title"])
    val row = (generic.raw["entities"] as JsonArray).single().jsonObject
    assertEquals("light.kitchen", row["entity"]!!.jsonPrimitive.content)
    assertEquals("Kitchen", row["name"]!!.jsonPrimitive.content)
  }

  @Test
  fun nestedEntitiesAreCollectedInOrderWithoutDuplicates() {
    val generic =
      genericCard(
        card(
          """
          {"type":"custom:vertical-stack-in-card","title":"Garage",
           "cards":[
             {"type":"custom:mushroom-cover-card","entity":"cover.garage"},
             {"type":"custom:mini-graph-card","entities":["sensor.temp",{"entity":"light.kitchen","name":"Lamp"}]},
             {"type":"custom:button-card","entity":"cover.garage"}
           ]}
          """
        )
      )!!
    assertEquals("Garage", generic.raw["title"]!!.jsonPrimitive.content)
    assertEquals(listOf("cover.garage", "sensor.temp", "light.kitchen"), ids(generic))
    val lamp = (generic.raw["entities"] as JsonArray)[2].jsonObject
    assertEquals("Lamp", lamp["name"]!!.jsonPrimitive.content)
  }

  @Test
  fun aNestedCardKeepsItsNameIconAndTap() {
    val generic =
      genericCard(
        card(
          """
          {"type":"custom:stack-in-card","cards":[
            {"type":"custom:button-card","entity":"cover.garage","name":"Door","icon":"mdi:garage",
             "tap_action":{"action":"more-info"}}
          ]}
          """
        )
      )!!
    val row = (generic.raw["entities"] as JsonArray).single().jsonObject
    assertEquals("Door", row["name"]!!.jsonPrimitive.content)
    assertEquals("mdi:garage", row["icon"]!!.jsonPrimitive.content)
    assertEquals("more-info", row["tap_action"]!!.jsonObject["action"]!!.jsonPrimitive.content)
  }

  @Test
  fun onlyTheStandardEntityFieldsCount() {
    // A host pushes live values only for `entity` / `entities` (cardEntityIds), so other
    // entity-shaped strings must not become rows that would never update.
    assertNull(
      genericCard(
        card("""{"type":"custom:template-card","entity_id":"sun.sun","content":"sun.sun"}""")
      )
    )
  }

  @Test
  fun aCardWithoutEntitiesKeepsThePlaceholder() {
    assertNull(genericCard(card("""{"type":"custom:clock-weather-card","locale":"en"}""")))
    assertNull(genericCard(card("""{"type":"iframe","url":"https://example.com"}""")))
  }

  @Test
  fun theConverterDelegatesToTheGenericCard() {
    val custom = card("""{"type":"custom:mushroom-light-card","entity":"light.kitchen"}""")
    assertEquals("entities", UnsupportedCardConverter().designDelegate(custom, snapshot)?.type)
  }
}
