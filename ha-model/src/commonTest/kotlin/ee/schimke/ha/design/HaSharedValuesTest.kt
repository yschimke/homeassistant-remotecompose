package ee.schimke.ha.design

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class HaSharedValuesTest {
  private val sun =
    EntityState(
      "sun.sun",
      "below_horizon",
      buildJsonObject {
        put("elevation", -12.5)
        put("azimuth", 290.1)
        put("rising", false)
        put("next_rising", "2026-10-27T06:45:30.000000+00:00")
        put("next_setting", "2026-10-27T16:36:00+00:00")
        put("next_dusk", JsonPrimitive(null as String?))
      },
    )

  @Test
  fun sunValuesComeFromSunSun() {
    val values = HaSharedValues.bindings(HaSnapshot(states = mapOf("sun.sun" to sun)), TimeZone.UTC)
    assertEquals("below_horizon", values.strings["HA:sun.sun.state"])
    assertEquals(true, values.booleans["HA:sun.sun.is_available"])
    assertEquals(false, values.booleans["HA:sun.sun.above_horizon"])
    assertEquals(-12.5f, values.floats["HA:sun.sun.elevation"])
    assertEquals(290.1f, values.floats["HA:sun.sun.azimuth"])
    assertEquals(6 * 60 + 45.5f, values.floats["HA:sun.sun.next_rising.minute_of_day"])
    assertEquals(16 * 60 + 36f, values.floats["HA:sun.sun.next_setting.minute_of_day"])
    assertTrue(values.floats["HA:sun.sun.next_dusk.minute_of_day"]!!.isNaN())
  }

  @Test
  fun timesAreInTheGivenZone() {
    val values =
      HaSharedValues.bindings(
        HaSnapshot(states = mapOf("sun.sun" to sun)),
        TimeZone.of("America/Los_Angeles"),
      )
    // 16:36 UTC is 09:36 in Los Angeles on 27 October (PDT).
    assertEquals(9 * 60 + 36f, values.floats["HA:sun.sun.next_setting.minute_of_day"])
  }

  @Test
  fun everyDeclaredValueIsAlwaysPushed() {
    val values = HaSharedValues.bindings(HaSnapshot(), TimeZone.UTC)
    val pushed = values.strings.keys + values.booleans.keys + values.floats.keys
    assertEquals(HaSharedValues.values.map { it.name }.toSet(), pushed)
    assertEquals(false, values.booleans["HA:sun.sun.is_available"])
    assertEquals("", values.strings["HA:sun.sun.state"])
    assertTrue(values.floats["HA:sun.sun.elevation"]!!.isNaN())
  }

  @Test
  fun kindsMatchWhatIsPushed() {
    val values = HaSharedValues.bindings(HaSnapshot(), TimeZone.UTC)
    for (value in HaSharedValues.values) {
      val pushed =
        when (value.kind) {
          HaSharedValues.Kind.Text -> values.strings
          HaSharedValues.Kind.Bool -> values.booleans
          HaSharedValues.Kind.Float -> values.floats
        }
      assertTrue(value.name in pushed, value.name)
    }
  }

  @Test
  fun sharedValuesHaveADomainOfTheirOwn() {
    assertNotNull(HaSharedValues.valueOf("HA:sun.sun.elevation"))
    assertNull(HaSharedValues.valueOf("HA:lock.front_door.state"))
    assertTrue(HaSharedValues.isShared("HA:lock.front_door.state"))
    // A card's own values are in USER:, never shared.
    assertNull(HaSharedValues.valueOf("USER:sun.sun.elevation"))
    assertTrue(HaSharedValues.values.all { it.name.startsWith("HA:") })
  }
}
