package ee.schimke.ha.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class JinjaTemplateTest {

  private val json = Json { ignoreUnknownKeys = true }

  private fun attrs(vararg pairs: Pair<String, String>): JsonObject =
    json.parseToJsonElement(pairs.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" })
      as JsonObject

  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          "sensor.power" to EntityState("sensor.power", "1234.6"),
          "sensor.rate" to EntityState("sensor.rate", "0.2512"),
          "sensor.status" to EntityState("sensor.status", "printing"),
          "sensor.commit" to
            EntityState(
              "sensor.commit",
              "Fix the thing",
              attributes = attrs("sha" to "\"abcdef1234\"", "url" to "\"https://x/y\""),
            ),
          "sun.sun" to
            EntityState(
              "sun.sun",
              "above_horizon",
              attributes = attrs("elevation" to "12.34", "azimuth" to "187.5", "rising" to "false"),
            ),
          "binary_sensor.contact" to
            EntityState("binary_sensor.contact", "fresh", attributes = attrs()),
          "device_tracker.unifi_default_a" to EntityState("device_tracker.unifi_default_a", "home"),
          "device_tracker.unifi_default_b" to
            EntityState("device_tracker.unifi_default_b", "not_home"),
          "device_tracker.other" to EntityState("device_tracker.other", "home"),
        )
    )

  private fun render(t: String) = JinjaTemplate.render(t, snapshot)

  @Test
  fun plainStatesInterpolation() {
    assertEquals("Status: printing", render("Status: {{ states('sensor.status') }}"))
  }

  @Test
  fun floatRoundIntFilters() {
    assertEquals("1235 W", render("{{ states('sensor.power') | float(0) | round(0) | int }} W"))
    assertEquals("25.12 p", render("{{ (states('sensor.rate') | float(0) * 100) | round(2) }} p"))
  }

  @Test
  fun setAndIfElse() {
    val t =
      "{% set r = states('sensor.status') %}" +
        "{% if r not in ['unknown','idle'] %}Running: {{ r }}{% else %}–{% endif %}"
    assertEquals("Running: printing", render(t))
  }

  @Test
  fun ifElifElseChain() {
    val t =
      "{% set d = 0.5 %}" +
        "{% if d < 0.15 %}home{% elif d < 1 %}{{ (d*1000)|round(0)|int }} m{% elif d < 50 %}{{ d|round(2) }} km{% else %}far{% endif %}"
    assertEquals("500 m", render(t))
  }

  @Test
  fun statesObjectAccessAndSubscript() {
    assertEquals(
      "above horizon",
      render("{{ 'above horizon' if states.sun.sun.state == 'above_horizon' else 'below' }}"),
    )
    assertEquals(
      "12.3 / 188",
      render(
        "{{ states.sun.sun.attributes.elevation | round(1) }} / {{ states.sun.sun.attributes.azimuth | round(0) | int }}"
      ),
    )
  }

  @Test
  fun stateObjectTruthinessAndStateAttrSlice() {
    val t =
      "{% set s = states['sensor.commit'] %}" +
        "{% if s and s.state not in ['unavailable','unknown'] %}{{ s.state }} {{ state_attr('sensor.commit','sha')[:7] }}{% else %}none{% endif %}"
    assertEquals("Fix the thing abcdef1", render(t))
  }

  @Test
  fun missingEntitySubscriptIsFalsy() {
    val t = "{% set s = states['sensor.absent'] %}{% if s %}yes{% else %}no{% endif %}"
    assertEquals("no", render(t))
  }

  @Test
  fun isNumberAndOrDefault() {
    assertEquals(
      "–",
      render("{{ state_attr('binary_sensor.contact','last_advert_formatted') or '–' }}"),
    )
    val t =
      "{% set lat = state_attr('binary_sensor.contact','latitude') %}" +
        "{% if lat is number %}has{% else %}missing{% endif %}"
    assertEquals("missing", render(t))
  }

  @Test
  fun listIndexAndTitle() {
    val t =
      "{% set comp = ['N','NE','E','SE','S'] %}{% set idx = ((180 / 45) | round(0,'floor')) | int %}{{ comp[idx] }}"
    assertEquals("S", render(t))
    assertEquals("partly cloudy", render("{{ 'partly-cloudy' | replace('-', ' ') }}"))
    assertEquals("Hello World", render("{{ 'hello world' | title }}"))
    assertEquals("Partly Cloudy", render("{{ 'partly-cloudy' | replace('-', ' ') | title }}"))
  }

  @Test
  fun selectattrMatchEqCount() {
    val t =
      "{{ states.device_tracker | selectattr('entity_id','match','^device_tracker.unifi_default_') | selectattr('state','eq','home') | list | count }}"
    assertEquals("1", render(t))
  }

  @Test
  fun distanceWithoutHomeFallsToNoLocationBranch() {
    val t =
      "{% set lat = state_attr('binary_sensor.contact','latitude') %}" +
        "{% set d = distance(lat, 0) if lat is number else none %}" +
        "{% if d is not none %}{{ d }}{% else %}no location{% endif %}"
    assertEquals("no location", render(t))
  }

  @Test
  fun asTimestampDifferenceRendersANumber() {
    val out = render("{{ ((as_timestamp(now()) - as_timestamp(now())) | int) }}")
    assertEquals("0", out)
  }

  @Test
  fun unsupportedConstructReturnsNull() {
    // `for` loops are not supported — must return null, not throw.
    assertNull(render("{% for x in [1,2] %}{{ x }}{% endfor %}"))
    assertNull(render("{{ 1 |"))
  }

  @Test
  fun integerArithmeticStaysIntegral() {
    assertEquals("6", render("{{ 2 * 3 }}"))
    assertEquals("2.5", render("{{ 5 / 2 }}"))
    assertEquals("2", render("{{ 5 // 2 }}"))
  }
}
