package ee.schimke.terrazzo.widget

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class WidgetPartsTest {

  private fun card(json: String): CardConfig {
    val raw = Json.parseToJsonElement(json).jsonObject
    return CardConfig(type = (raw["type"] as JsonPrimitive).content, raw = raw)
  }

  @Test
  fun aHorizontalStackOffersEachOfItsCards() {
    val stack =
      card(
        """{"type":"horizontal-stack","cards":[
          {"type":"tile","entity":"switch.boiler"},
          {"type":"tile","entity":"sensor.boiler_power"}]}"""
      )
    assertEquals(
      listOf("switch.boiler", "sensor.boiler_power"),
      stack.widgetParts().map { (it.raw["entity"] as JsonPrimitive).content },
    )
  }

  @Test
  fun aGridAndAVerticalStackAreContainersToo() {
    val children = """[{"type":"tile","entity":"light.a"},{"type":"tile","entity":"light.b"}]"""
    assertEquals(2, card("""{"type":"grid","cards":$children}""").widgetParts().size)
    assertEquals(2, card("""{"type":"vertical-stack","cards":$children}""").widgetParts().size)
  }

  @Test
  fun aSingleCardOrAOneCardStackHasNothingToChoose() {
    assertTrue(card("""{"type":"tile","entity":"light.a"}""").widgetParts().isEmpty())
    assertTrue(
      card("""{"type":"horizontal-stack","cards":[{"type":"tile","entity":"light.a"}]}""")
        .widgetParts()
        .isEmpty()
    )
  }

  @Test
  fun aPartIsLabelledByItsNameThenItsEntity() {
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "sensor.boiler_power" to
              EntityState(
                "sensor.boiler_power",
                "70.9",
                JsonObject(mapOf("friendly_name" to JsonPrimitive("Boiler power"))),
              )
          )
      )
    assertEquals(
      "Boiler",
      widgetPartLabel(
        card("""{"type":"tile","entity":"switch.boiler","name":"Boiler"}"""),
        snapshot,
      ),
    )
    assertEquals(
      "Boiler power",
      widgetPartLabel(card("""{"type":"tile","entity":"sensor.boiler_power"}"""), snapshot),
    )
    assertEquals(
      "switch.other",
      widgetPartLabel(card("""{"type":"tile","entity":"switch.other"}"""), snapshot),
    )
  }
}
