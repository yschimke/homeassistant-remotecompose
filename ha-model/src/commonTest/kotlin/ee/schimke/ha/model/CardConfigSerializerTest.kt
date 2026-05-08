package ee.schimke.ha.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class CardConfigSerializerTest {

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  @Test
  fun decode_capturesEveryFieldOfTheCardObjectAsRaw() {
    val payload =
      """
      {
        "type": "tile",
        "entity": "light.kitchen",
        "name": "Kitchen",
        "icon": "mdi:lightbulb"
      }
      """
        .trimIndent()

    val card = json.decodeFromString(CardConfig.serializer(), payload)

    assertEquals("tile", card.type)
    assertEquals("light.kitchen", card.raw["entity"]?.jsonPrimitive?.content)
    assertEquals("Kitchen", card.raw["name"]?.jsonPrimitive?.content)
    assertEquals("mdi:lightbulb", card.raw["icon"]?.jsonPrimitive?.content)
  }

  @Test
  fun decode_inDashboard_carriesCardFieldsThroughViewsAndSections() {
    val payload =
      """
      {
        "title": "Home",
        "views": [
          {
            "title": "Main",
            "cards": [
              { "type": "tile", "entity": "light.kitchen" }
            ],
            "sections": [
              {
                "type": "grid",
                "cards": [
                  { "type": "button", "entity": "switch.lamp", "name": "Lamp" }
                ]
              }
            ]
          }
        ]
      }
      """
        .trimIndent()

    val dashboard = json.decodeFromString(Dashboard.serializer(), payload)

    val orphan = dashboard.views[0].cards[0]
    assertEquals("tile", orphan.type)
    assertEquals("light.kitchen", orphan.raw["entity"]?.jsonPrimitive?.content)

    val sectionCard = dashboard.views[0].sections[0].cards[0]
    assertEquals("button", sectionCard.type)
    assertEquals("switch.lamp", sectionCard.raw["entity"]?.jsonPrimitive?.content)
    assertEquals("Lamp", sectionCard.raw["name"]?.jsonPrimitive?.content)
  }

  @Test
  fun encode_writesTypeAtTopLevelEvenWhenAbsentFromRaw() {
    val card = CardConfig(type = "tile", raw = JsonObject(emptyMap()))

    val encoded = json.encodeToString(CardConfig.serializer(), card)
    val roundTrip = json.decodeFromString(CardConfig.serializer(), encoded)

    assertEquals("tile", roundTrip.type)
    assertEquals(JsonPrimitive("tile"), roundTrip.raw["type"])
  }

  @Test
  fun encode_preservesExistingTypeFieldInRaw() {
    val raw =
      JsonObject(mapOf("type" to JsonPrimitive("tile"), "entity" to JsonPrimitive("light.kitchen")))
    val card = CardConfig(type = "tile", raw = raw)

    val encoded = json.encodeToString(CardConfig.serializer(), card)

    // No duplicated type fields, and entity survives.
    val firstTypeIndex = encoded.indexOf("\"type\"")
    val lastTypeIndex = encoded.lastIndexOf("\"type\"")
    assertTrue(
      firstTypeIndex >= 0 && firstTypeIndex == lastTypeIndex,
      "expected single type key, got: $encoded",
    )
    assertTrue(encoded.contains("light.kitchen"), "expected entity preserved, got: $encoded")
  }
}
