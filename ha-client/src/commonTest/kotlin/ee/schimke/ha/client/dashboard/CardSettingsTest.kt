package ee.schimke.ha.client.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class CardSettingsTest {
  private fun card(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

  @Test
  fun eachTypeHasTheFormItsSettingsNeed() {
    val tile = CardSettings.fields(card("""{"type":"tile","entity":"light.desk"}"""))
    assertEquals(
      listOf("entity", "name", "icon", "vertical", "hide_state", "tap_action"),
      tile.map { it.key },
    )
    assertEquals(
      listOf("title", "entities", "hours_to_show"),
      CardSettings.fields(card("""{"type":"history-graph"}""")).map { it.key },
    )
    // A type the form doesn't know is edited as JSON only.
    assertTrue(CardSettings.fields(card("""{"type":"custom:mushroom-card"}""")).isEmpty())
  }

  @Test
  fun textIsSetAndBlankRemovesTheKey() {
    val tile = card("""{"type":"tile","entity":"light.desk"}""")
    val named = CardSettings.withText(tile, "name", "Desk")
    assertEquals("Desk", CardSettings.text(named, "name"))
    assertEquals(tile, CardSettings.withText(named, "name", "  "))
  }

  @Test
  fun numbersStayWholeAndRefuseText() {
    val gauge = card("""{"type":"gauge","entity":"sensor.soil"}""")
    val max = CardSettings.withNumber(gauge, "max", " 100 ")
    assertEquals("""{"type":"gauge","entity":"sensor.soil","max":100}""", max.toString())
    assertEquals("0.5", CardSettings.number(CardSettings.withNumber(max, "min", "0.5"), "min"))
    assertEquals("100", CardSettings.number(max, "max"))
    assertEquals(gauge, CardSettings.withNumber(max, "max", ""))
    assertFailsWith<IllegalArgumentException> { CardSettings.withNumber(gauge, "max", "lots") }
    assertFalse(CardSettings.isNumber("lots"))
    assertTrue(CardSettings.isNumber(""))
  }

  @Test
  fun aToggleAtItsDefaultLeavesNoKey() {
    val button = card("""{"type":"button","entity":"switch.pump"}""")
    val field =
      CardSettings.fields(button).filterIsInstance<CardField.Toggle>().first {
        it.key == "show_state"
      }
    assertFalse(CardSettings.toggle(button, field))
    val shown = CardSettings.withToggle(button, field, true)
    assertTrue(CardSettings.toggle(shown, field))
    assertEquals(button, CardSettings.withToggle(shown, field, false))
  }

  @Test
  fun entityRowsKeepTheirOwnSettings() {
    val entities =
      card("""{"type":"entities","entities":["sensor.a",{"entity":"sensor.b","name":"B"}]}""")
    assertEquals(listOf("sensor.a", "sensor.b"), CardSettings.entityIds(entities))
    val added = CardSettings.withEntityAdded(entities, "sensor.c")
    assertEquals(listOf("sensor.a", "sensor.b", "sensor.c"), CardSettings.entityIds(added))
    val removed = CardSettings.withEntityRemoved(added, 0)
    assertEquals(
      """[{"entity":"sensor.b","name":"B"},"sensor.c"]""",
      removed["entities"].toString(),
    )
    val moved = CardSettings.withEntityMoved(removed, 1, -1)
    assertEquals(listOf("sensor.c", "sensor.b"), CardSettings.entityIds(moved))
    assertFailsWith<IllegalArgumentException> { CardSettings.withEntityRemoved(moved, 5) }
  }

  @Test
  fun aTapActionWithMoreSettingsKeepsThemUntilChanged() {
    val tile =
      card(
        """{"type":"tile","entity":"light.desk",
          "tap_action":{"action":"navigate","navigation_path":"/lights"}}"""
      )
    assertEquals("navigate", CardSettings.tapAction(tile))
    // The form offers the card's own action, so showing it changes nothing.
    assertTrue(CardSettings.tapActions(tile).any { it.first == "navigate" })
    assertEquals(tile, CardSettings.withTapAction(tile, "navigate"))
    val toggles = CardSettings.withTapAction(tile, "toggle")
    assertEquals("""{"action":"toggle"}""", toggles["tap_action"].toString())
    assertEquals("", CardSettings.tapAction(CardSettings.withTapAction(toggles, "")))
  }

  @Test
  fun aChoiceOffersAValueSetElsewhere() {
    val clock = card("""{"type":"clock","clock_size":"huge"}""")
    val size = CardSettings.fields(clock).filterIsInstance<CardField.Choice>().first()
    assertEquals("huge", CardSettings.choices(clock, size).last().first)
    assertEquals(4, CardSettings.choices(clock, size).size)
  }
}
