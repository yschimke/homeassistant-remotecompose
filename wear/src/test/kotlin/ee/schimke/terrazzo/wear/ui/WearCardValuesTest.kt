package ee.schimke.terrazzo.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    assertEquals(values, values.forCard(null))
  }

  @Test
  fun `a card skips when only another card's entity changes`() {
    val raw = """{"type":"tile","entity":"light.kitchen"}"""
    var values by
      mutableStateOf(
        mapOf(
          "light.kitchen" to EntityValue(state = "on"),
          "sensor.power" to EntityValue(state = "100", unit = "W"),
        )
      )
    compose.setContent { Card(rememberCardValues(raw, values)) }
    compose.waitForIdle()
    assertEquals(1, cardCompositions)

    values = values + ("sensor.power" to EntityValue(state = "120", unit = "W"))
    compose.waitForIdle()
    assertEquals(1, cardCompositions, "another card's value leaves this card alone")

    values = values + ("light.kitchen" to EntityValue(state = "off"))
    compose.waitForIdle()
    assertEquals(2, cardCompositions, "its own value recomposes it")
  }

  private var cardCompositions = 0

  /** Stands in for a card: takes only its values, so it skips while they stay the same. */
  @Composable
  private fun Card(values: Map<String, EntityValue>) {
    cardCompositions++
    check(values.isNotEmpty())
  }
}
