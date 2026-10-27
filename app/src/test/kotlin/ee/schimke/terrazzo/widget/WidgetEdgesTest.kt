package ee.schimke.terrazzo.widget

import ee.schimke.ha.model.EntityState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What redraws a widget at once, matching the integration's `state_wakes.py`. */
class WidgetEdgesTest {
  private fun state(id: String, state: String) = EntityState(entityId = id, state = state)

  @Test
  fun aMovementStartingOrEndingIsVisible() {
    val closed = state("cover.garage", "closed")
    val opening = state("cover.garage", "opening")
    val open = state("cover.garage", "open")
    assertTrue(WidgetEdges.isVisible("cover.garage", closed, opening))
    assertTrue(WidgetEdges.isVisible("cover.garage", opening, open))
    assertTrue(WidgetEdges.isMoving(opening))
    assertFalse(WidgetEdges.isMoving(open))
  }

  @Test
  fun aStateFlippingIsVisible() {
    assertTrue(
      WidgetEdges.isVisible("light.porch", state("light.porch", "off"), state("light.porch", "on"))
    )
    assertTrue(
      WidgetEdges.isVisible(
        "sensor.power",
        state("sensor.power", "120"),
        state("sensor.power", "unavailable"),
      )
    )
  }

  @Test
  fun attributesAndNumbersTickingAreNot() {
    val on = state("light.porch", "on")
    assertFalse(WidgetEdges.isVisible("light.porch", on, on.copy(attributes = on.attributes)))
    assertFalse(
      WidgetEdges.isVisible(
        "sensor.power",
        state("sensor.power", "120.5"),
        state("sensor.power", "121"),
      )
    )
    // Only some domains move: a sensor reading "opening" is just a state.
    assertFalse(WidgetEdges.isMoving(state("sensor.door", "opening")))
  }
}
