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
        card("""{"type":"custom:mushroom-light-card","entity":"light.kitchen","name":"Kitchen"}"""),
        snapshot,
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
        ),
        snapshot,
      )!!
    assertEquals("Garage", generic.raw["title"]!!.jsonPrimitive.content)
    assertEquals(listOf("cover.garage", "sensor.temp", "light.kitchen"), ids(generic))
  }

  @Test
  fun looseStringsCountOnlyWhenTheSnapshotKnowsThem() {
    val generic =
      genericCard(
        card("""{"type":"custom:template-card","content":"sun.sun","secondary":"not.an_entity"}"""),
        snapshot,
      )!!
    assertEquals(listOf("sun.sun"), ids(generic))
  }

  @Test
  fun aCardWithoutEntitiesKeepsThePlaceholder() {
    assertNull(
      genericCard(card("""{"type":"custom:clock-weather-card","locale":"en"}"""), snapshot)
    )
    assertNull(genericCard(card("""{"type":"iframe","url":"https://example.com"}"""), snapshot))
  }

  @Test
  fun theConverterDelegatesToTheGenericCard() {
    val custom = card("""{"type":"custom:mushroom-light-card","entity":"light.kitchen"}""")
    assertEquals("entities", UnsupportedCardConverter().designDelegate(custom, snapshot)?.type)
  }
}
