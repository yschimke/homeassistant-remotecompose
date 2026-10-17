package ee.schimke.ha.model

import kotlin.test.*
import kotlinx.serialization.json.*

class LovelaceTemplateTest {
  private val source =
    Json.parseToJsonElement(
        """{
    "type":"grid","title":"sensor.s0","x-custom":{"untouched":true},
    "cards":[{"type":"tile","entity":"climate.s0"},
      {"type":"entities","entities":["sensor.s0",{"entity":"sensor.s0","name":"Temperature"}]},
      {"type":"button","tap_action":{"action":"perform-action","target":{"entity_id":["climate.s0"]}}}]
  }"""
      )
      .jsonObject
  private val template =
    LovelaceTemplate(
      "heating",
      "Heating",
      LovelaceTemplate.Kind.Section,
      source,
      listOf(
        LovelaceEntitySlot("climate.s0", "climate"),
        LovelaceEntitySlot("sensor.s0", "sensor", "temperature"),
      ),
    )
  private val states =
    mapOf(
      "climate.boiler" to EntityState("climate.boiler", "heat"),
      "sensor.room" to
        EntityState("sensor.room", "21", buildJsonObject { put("device_class", "temperature") }),
      "sensor.power" to
        EntityState("sensor.power", "500", buildJsonObject { put("device_class", "power") }),
    )
  private val bindings = mapOf("climate.s0" to "climate.boiler", "sensor.s0" to "sensor.room")

  @Test
  fun placementBindsOnlyEntityReferencesIncludingActionsAndCustomRows() {
    val bound = template.bind(bindings, states)
    val cards = bound["cards"]!!.jsonArray
    assertEquals(JsonPrimitive("climate.boiler"), cards[0].jsonObject["entity"])
    assertEquals(JsonPrimitive("sensor.room"), cards[1].jsonObject["entities"]!!.jsonArray[0])
    assertEquals(
      JsonPrimitive("sensor.room"),
      cards[1].jsonObject["entities"]!!.jsonArray[1].jsonObject["entity"],
    )
    assertEquals(
      JsonPrimitive("climate.boiler"),
      cards[2]
        .jsonObject["tap_action"]!!
        .jsonObject["target"]!!
        .jsonObject["entity_id"]!!
        .jsonArray[0],
    )
    assertEquals(source["title"], bound["title"])
    assertEquals(source["x-custom"], bound["x-custom"])
    assertEquals(JsonPrimitive("climate.s0"), source["cards"]!!.jsonArray[0].jsonObject["entity"])
  }

  @Test
  fun missingWrongDomainAndWrongDeviceClassAreRefusedBeforePlacement() {
    assertFailsWith<IllegalArgumentException> { template.bind(bindings - "sensor.s0", states) }
    assertFailsWith<IllegalArgumentException> {
      template.bind(bindings + ("climate.s0" to "sensor.room"), states)
    }
    assertFailsWith<IllegalArgumentException> {
      template.bind(bindings + ("sensor.s0" to "sensor.power"), states)
    }
    assertFails { template.bind(bindings, states - "sensor.room") }
    assertFailsWith<IllegalArgumentException> {
      template.copy(slots = template.slots + template.slots[0]).bind(bindings, states)
    }
  }

  @Test
  fun wholeViewTemplatesBindNestedSectionsAndLeaveUnknownConfigAlone() {
    val view =
      template.copy(
        kind = LovelaceTemplate.Kind.View,
        config =
          buildJsonObject {
            put("type", "sections")
            put("path", "heating")
            put("sections", JsonArray(listOf(source)))
          },
      )
    assertEquals(
      template.bind(bindings, states),
      view.bind(bindings, states)["sections"]!!.jsonArray[0],
    )
  }
}
