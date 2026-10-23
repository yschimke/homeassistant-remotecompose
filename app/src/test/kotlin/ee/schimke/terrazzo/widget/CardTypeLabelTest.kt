package ee.schimke.terrazzo.widget

import kotlin.test.Test
import kotlin.test.assertEquals

class CardTypeLabelTest {

  @Test
  fun aCardTypeReadsAsWords() {
    assertEquals("Tile card", cardTypeLabel("tile"))
    assertEquals("Weather forecast card", cardTypeLabel("weather-forecast"))
    assertEquals("Todo list card", cardTypeLabel("todo_list"))
  }

  @Test
  fun aCustomCardKeepsItsOwnName() {
    assertEquals("Ha bambulab print status card", cardTypeLabel("custom:ha-bambulab-print-status"))
  }

  @Test
  fun anEmptyTypeIsStillACard() {
    assertEquals("Card", cardTypeLabel(""))
    assertEquals("Card", cardTypeLabel("custom:"))
  }
}
