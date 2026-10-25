package ee.schimke.terrazzo.widget

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class WidgetPartsTest {

  private fun card(json: String): CardConfig {
    val raw = Json.parseToJsonElement(json).jsonObject
    return CardConfig(type = (raw["type"] as JsonPrimitive).content, raw = raw)
  }

  private val snapshot =
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

  private val boilerRow =
    card(
      """{"type":"horizontal-stack","cards":[
        {"type":"tile","entity":"switch.boiler","name":"Boiler"},
        {"type":"tile","entity":"sensor.boiler_power"}]}"""
    )
  private val history = card("""{"type":"history-graph","title":"Boiler power & status"}""")

  @Test
  fun aRowInASectionOffersTheSectionTheRowAndEachCell() {
    val parts = widgetParts(boilerRow, listOf(boilerRow, history), snapshot)
    assertEquals(
      listOf("Whole section", "Row of 2", "Boiler", "Boiler power"),
      parts.map { it.label },
    )
    val section = parts[0].card
    assertEquals("vertical-stack", section.type)
    assertEquals(JsonArray(listOf(boilerRow.raw, history.raw)), section.raw["cards"])
    assertEquals(boilerRow, parts[1].card)
    assertEquals("sensor.boiler_power", (parts[3].card.raw["entity"] as JsonPrimitive).content)
  }

  @Test
  fun aPlainCardInASectionOffersTheSectionOrTheCard() {
    assertEquals(
      listOf("Whole section", "Boiler power & status"),
      widgetParts(history, listOf(boilerRow, history), snapshot).map { it.label },
    )
  }

  @Test
  fun nestedContainersOfferEveryLevel() {
    val grid =
      card(
        """{"type":"grid","cards":[
          {"type":"vertical-stack","cards":[
            {"type":"tile","entity":"light.a"},{"type":"tile","entity":"light.b"}]},
          {"type":"tile","entity":"light.c"}]}"""
      )
    assertEquals(
      listOf("Grid of 2", "Column of 2", "light.a", "light.b", "light.c"),
      widgetParts(grid, emptyList(), snapshot).map { it.label },
    )
  }

  @Test
  fun nothingToChooseForALoneCardOrTheOnlyCardInASection() {
    assertTrue(widgetParts(history, emptyList(), snapshot).isEmpty())
    assertTrue(widgetParts(history, listOf(history), snapshot).isEmpty())
    val oneCardStack =
      card("""{"type":"horizontal-stack","cards":[{"type":"tile","entity":"light.a"}]}""")
    assertTrue(widgetParts(oneCardStack, emptyList(), snapshot).isEmpty())
  }

  @Test
  fun aSectionThatDoesNotHoldTheCardIsNotOffered() {
    assertEquals(
      listOf("Row of 2", "Boiler", "Boiler power"),
      widgetParts(boilerRow, listOf(history, history), snapshot).map { it.label },
    )
  }

  @Test
  fun aPartIsLabelledByItsNameThenItsEntity() {
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
