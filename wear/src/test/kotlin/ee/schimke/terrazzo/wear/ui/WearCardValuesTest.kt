package ee.schimke.terrazzo.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearCardValuesTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `a card reads the entities its config names`() {
    val raw =
      """{"type":"entities","entities":["light.kitchen",{"entity":"sensor.power"}],""" +
        """"content":"{{ states('sensor.outside_temp') }}","name":"Kitchen. Lights"}"""

    assertEquals(
      setOf("light.kitchen", "sensor.power", "sensor.outside_temp"),
      wearCardEntityIds(raw),
    )
  }

  @Test
  fun `a Bambu Lab card keeps every value`() {
    // Its printer's other sensors come from a device registry the watch doesn't have.
    val raw = """{"type":"custom:ha-bambulab-print_status-card","printer":"abc123"}"""

    assertNull(wearCardEntityIds(raw))
    val values = mapOf("sensor.p1s_nozzle" to EntityValue(state = "210"))
    assertEquals(values, WearValues(values).forIds(null))
  }

  @Test
  fun `an entity first read after an update reads the update`() {
    val values = WearValues(mapOf("light.kitchen" to EntityValue(state = "on")))
    values.update(mapOf("light.kitchen" to EntityValue(state = "off")))

    assertEquals("off", values["light.kitchen"]?.state)
    assertNull(values["sensor.missing"])
  }

  @Test
  fun `a value update recomposes only the card that shows it`() {
    val raw = """{"type":"tile","entity":"light.kitchen"}"""
    val values =
      WearValues(
        mapOf(
          "light.kitchen" to EntityValue(state = "on"),
          "sensor.power" to EntityValue(state = "100", unit = "W"),
        )
      )
    compose.setContent { Screen(raw, values) }
    compose.waitForIdle()
    assertEquals(1, screenCompositions)
    assertEquals(1, cardCompositions)

    values.update(values.all() + ("sensor.power" to EntityValue(state = "120", unit = "W")))
    compose.waitForIdle()
    assertEquals(1, cardCompositions, "another card's value leaves this card alone")

    values.update(values.all() + ("light.kitchen" to EntityValue(state = "off")))
    compose.waitForIdle()
    assertEquals(2, cardCompositions, "its own value recomposes it")
    assertEquals(1, screenCompositions, "the screen holding the values never recomposes")
  }

  private var screenCompositions = 0
  private var cardCompositions = 0

  /** Stands in for a screen: passes the values on and reads none of them. */
  @Composable
  private fun Screen(raw: String, values: WearValues) {
    screenCompositions++
    Card(raw, values)
  }

  /** Stands in for a card: reads its own entities. */
  @Composable
  private fun Card(raw: String, values: WearValues) {
    cardCompositions++
    check(rememberCardValues(raw, values).isNotEmpty())
  }
}
