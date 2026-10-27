package ee.schimke.terrazzo.widget

import ee.schimke.ha.model.CardConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class WidgetLabelTest {
  private val card =
    CardConfig(
      "tile",
      JsonObject(mapOf("entity" to JsonPrimitive("light.desk"), "name" to JsonPrimitive("Desk"))),
    )

  @Test
  fun aLabelReplacesTheName() {
    assertEquals(JsonPrimitive("Lamp"), card.withLabel(" Lamp ").raw["name"])
    assertEquals(JsonPrimitive("light.desk"), card.withLabel("Lamp").raw["entity"])
  }

  @Test
  fun theSameLabelKeepsTheCard() {
    assertSame(card, card.withLabel("Desk"))
  }

  @Test
  fun aBlankLabelFallsBackToTheEntityName() {
    assertEquals(null, card.withLabel("  ").raw["name"])
  }
}
