package ee.schimke.terrazzo.shared

import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.ha.model.*
import kotlin.test.*
import kotlinx.serialization.json.*

class InstrumentTemplateBindingsTest {
  private fun card(type: String, id: String, extra: String = "") =
    CardConfig(
      type,
      Json.parseToJsonElement("""{"type":"$type","entity":"$id"$extra}""").jsonObject,
    )

  private fun climate(attributes: String) =
    HaSnapshot(
      mapOf(
        "climate.demo_thermostat" to
          EntityState(
            "climate.demo_thermostat",
            "heat",
            Json.parseToJsonElement(attributes).jsonObject,
          )
      )
    )

  @Test
  fun gaugeUsesItsLiveRangeSeverityAndReading() {
    val card =
      card(
        "gauge",
        "sensor.power",
        """, "min":0,"max":3000,"severity":{"yellow":1600,"red":2400}""",
      )
    fun values(state: String) =
      templateBindings(
        card,
        HaSnapshot(
          mapOf(
            "sensor.power" to
              EntityState(
                "sensor.power",
                state,
                buildJsonObject { put("unit_of_measurement", "W") },
              )
          )
        ),
      )
    assertEquals(RcNamedValue.FloatValue(3000f), values("900")["card.max"])
    assertEquals(RcNamedValue.FloatValue(1600f), values("900")["card.severity_yellow"])
    assertEquals(RcNamedValue.Text("0 – 3000 W"), values("900")["card.range"])
    assertEquals(RcNamedValue.FloatValue(900f), values("900")["sensor.s0.numeric_state"])
    assertEquals(RcNamedValue.FloatValue(1200f), values("1200")["sensor.s0.numeric_state"])
    assertEquals(RcNamedValue.Text("Unavailable"), values("unavailable")["sensor.s0.state"])
  }

  @Test
  fun thermostatFollowsBothTemperaturesAndClearsRemovedTargets() {
    val card = card("thermostat", "climate.demo_thermostat")
    val before =
      templateBindings(
        card,
        climate(
          """{"current_temperature":21.5,"temperature":22,"min_temp":16,"max_temp":28,"hvac_action":"heating"}"""
        ),
      )
    val after =
      templateBindings(
        card,
        climate(
          """{"current_temperature":22,"temperature":23,"min_temp":16,"max_temp":28,"hvac_action":"idle"}"""
        ),
      )
    assertEquals(
      RcNamedValue.Text("21.5°C"),
      before["climate.s0.attributes.current_temperature_label"],
    )
    assertEquals(RcNamedValue.Text("↑ 23°C"), after["climate.s0.attributes.temperature_label"])
    assertEquals(RcNamedValue.Text("Idle"), after["climate.s0.attributes.hvac_action_label"])
    assertNotEquals(before["climate.s0.value_fraction"], after["climate.s0.value_fraction"])
    assertNotEquals(before["climate.s0.target_fraction"], after["climate.s0.target_fraction"])
    val missing = templateBindings(card, climate("{}"))
    assertEquals(RcNamedValue.Text("—"), missing["climate.s0.attributes.temperature_label"])
    assertEquals(RcNamedValue.FloatValue(0f), missing["climate.s0.target_fraction"])
  }

  @Test
  fun thermostatLabelsUseTheInstancesUnitSystem() {
    val card = card("thermostat", "climate.demo_thermostat")
    val snapshot =
      climate("""{"current_temperature":68.5,"temperature":70,"min_temp":45,"max_temp":95}""")
        .copy(temperatureUnit = "°F")
    val values = templateBindings(card, snapshot)
    assertEquals(
      RcNamedValue.Text("68.5°F"),
      values["climate.s0.attributes.current_temperature_label"],
    )
    assertEquals(RcNamedValue.Text("↑ 70°F"), values["climate.s0.attributes.temperature_label"])
  }

  @Test
  fun thermostatActionsRebuildFromLiveTargetAndRejectUnrelatedPayloads() {
    val card = card("thermostat", "climate.demo_thermostat")
    val snapshot = climate("""{"temperature":23}""")
    fun payload(value: Double, entity: String = "climate.s0") =
      """{"type":"CallService","domain":"climate","service":"set_temperature","entityId":"$entity","serviceData":{"temperature":$value}}"""
    assertEquals(23.5, demoThermostatTarget(card, snapshot, payload(20.5)))
    assertEquals(22.5, demoThermostatTarget(card, snapshot, payload(19.5)))
    assertNull(demoThermostatTarget(card, snapshot, payload(28.0)))
    assertNull(demoThermostatTarget(card, snapshot, payload(20.5, "climate.private")))
    assertNull(demoThermostatTarget(card, climate("{}"), payload(20.5)))
    assertNull(demoThermostatTarget(card, climate("""{"temperature":28}"""), payload(20.5)))
  }

  @Test
  fun dashboardToggleConfigurationWorksWithGenericMoreInfoTemplates() {
    val configured = card("button", "switch.demo_coffee", """, "tap_action":{"action":"toggle"}""")
    val payload = """{"type":"MoreInfo","entityId":"switch.s0"}"""
    assertEquals("switch.demo_coffee", demoToggleEntity(configured, payload))
    assertNull(demoToggleEntity(card("button", "switch.demo_coffee"), payload))
    assertEquals("switch.demo_coffee", demoMoreInfoEntity(configured, payload))
  }
}
