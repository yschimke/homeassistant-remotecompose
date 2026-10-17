package ee.schimke.ha.rc

import ee.schimke.ha.design.DeviceInfo
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Table-driven cases for [formatState]: each reads the way Home Assistant's frontend shows it. */
class HaStateFormatCasesTest {

  private val now = Instant.parse("2026-04-20T15:22:53Z")

  private fun entity(id: String, state: String, vararg attributes: Pair<String, Any>) =
    EntityState(
      id,
      state,
      buildJsonObject {
        attributes.forEach { (k, v) ->
          put(k, if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()))
        }
      },
    )

  private fun format(entity: EntityState?, precision: Int? = null) =
    formatStateIn(entity, precision, now, TimeZone.UTC)

  private fun assertCases(cases: List<Pair<EntityState, String>>) {
    for ((entity, expected) in cases) {
      assertEquals(
        expected,
        format(entity),
        "${entity.entityId}=${entity.state} ${entity.attributes}",
      )
    }
  }

  // C1 ----------------------------------------------------------------------------------------

  @Test
  fun timestampsReadAsRelativeTime() {
    fun ago(d: Duration) = (now - d).toString()
    fun ahead(d: Duration) = (now + d).toString()
    val ts = "device_class" to "timestamp"
    assertCases(
      listOf(
        entity("event.back_door_motion", "2026-04-20T12:22:53.298+00:00") to "3 h ago",
        entity("sensor.last_boot", ago(10.seconds), ts) to "just now",
        entity("sensor.last_boot", ago(50.seconds), ts) to "1 min ago",
        entity("sensor.last_boot", ago(5.minutes + 59.seconds), ts) to "6 min ago",
        entity("sensor.last_boot", ago(44.minutes), ts) to "44 min ago",
        entity("sensor.last_boot", ago(50.minutes), ts) to "1 h ago",
        entity("sensor.last_boot", ago(1.hours), ts) to "1 h ago",
        entity("sensor.last_boot", ago(21.hours), ts) to "21 h ago",
        entity("sensor.last_boot", ago(23.hours), ts) to "1 day ago",
        entity("sensor.last_boot", ago(1.days), ts) to "1 day ago",
        entity("sensor.last_boot", ago(6.days), ts) to "6 days ago",
        entity("sensor.next_alarm", ahead(5.minutes + 10.seconds), ts) to "in 5 min",
        entity("sensor.next_alarm", ahead(3.hours), ts) to "in 3 h",
        entity("sensor.next_alarm", ahead(2.days), ts) to "in 2 days",
        entity("sensor.next_alarm", ahead(20.seconds), ts) to "now",
        // A week or more away: a date, not "26 weeks ago".
        entity("sensor.last_boot", "2025-12-25T08:00:00+00:00", ts) to "25 Dec 2025",
        entity("sensor.next_alarm", "2026-05-01T00:30:00+02:00", ts) to "30 Apr 2026",
        entity("button.restart", ago(2.hours)) to "2 h ago",
        entity("input_button.ring", ago(2.minutes)) to "2 min ago",
        entity("scene.evening", ago(3.days)) to "3 days ago",
      )
    )
  }

  @Test
  fun aTimestampThatDoesNotParseIsShownAsGiven() {
    assertEquals("Soon", format(entity("sensor.x", "Soon", "device_class" to "timestamp")))
    assertEquals("Unknown", format(entity("event.doorbell", "unknown")))
  }

  @Test
  fun dateDeviceClassReadsAsADate() {
    assertEquals(
      "20 Apr 2026",
      format(entity("sensor.bin_day", "2026-04-20", "device_class" to "date")),
    )
  }

  @Test
  fun relativeTimeStatesAreRecognisedForRefresh() {
    assertTrue(isRelativeTimeState(entity("event.motion", "2026-04-20T12:22:53.298+00:00")))
    assertTrue(
      isRelativeTimeState(
        entity("sensor.last_boot", "2026-04-20T12:22:53+00:00", "device_class" to "timestamp")
      )
    )
    assertFalse(isRelativeTimeState(entity("event.motion", "unknown")))
    assertFalse(isRelativeTimeState(entity("sensor.temp", "21.5")))
    assertFalse(isRelativeTimeState(null))
  }

  @Test
  fun relativeTimeFoldsIntoTheDataSignature() {
    val id = "event.motion"
    val snapshot =
      HaSnapshot(states = mapOf(id to entity(id, Clock_now().minus(2.hours).toString())))
    assertTrue(cardDataSignature(setOf(id), snapshot).contains("~t2 h ago"))
    val plain = HaSnapshot(states = mapOf("sensor.t" to entity("sensor.t", "21")))
    assertFalse(cardDataSignature(setOf("sensor.t"), plain).contains("~t"))
  }

  @Suppress("FunctionName") private fun Clock_now() = kotlin.time.Clock.System.now()

  // B3 ----------------------------------------------------------------------------------------

  @Test
  fun binarySensorsUseTheirDeviceClassWords() {
    val table =
      listOf(
        "problem" to ("Problem" to "OK"),
        "connectivity" to ("Connected" to "Disconnected"),
        "door" to ("Open" to "Closed"),
        "garage_door" to ("Open" to "Closed"),
        "opening" to ("Open" to "Closed"),
        "window" to ("Open" to "Closed"),
        "lock" to ("Unlocked" to "Locked"),
        "moisture" to ("Wet" to "Dry"),
        "motion" to ("Detected" to "Clear"),
        "occupancy" to ("Detected" to "Clear"),
        "presence" to ("Home" to "Away"),
        "plug" to ("Plugged in" to "Unplugged"),
        "battery" to ("Low" to "Normal"),
        "battery_charging" to ("Charging" to "Not charging"),
        "running" to ("Running" to "Not running"),
        "safety" to ("Unsafe" to "Safe"),
        "smoke" to ("Detected" to "Clear"),
        "gas" to ("Detected" to "Clear"),
        "carbon_monoxide" to ("Detected" to "Clear"),
        "co" to ("Detected" to "Clear"),
        "sound" to ("Detected" to "Clear"),
        "tamper" to ("Tampering detected" to "Clear"),
        "update" to ("Update available" to "Up-to-date"),
        "vibration" to ("Detected" to "Clear"),
        "light" to ("Light detected" to "No light"),
        "heat" to ("Hot" to "Normal"),
        "cold" to ("Cold" to "Normal"),
        "moving" to ("Moving" to "Not moving"),
        // Home Assistant's own words for these are the generic ones.
        "power" to ("On" to "Off"),
      )
    for ((deviceClass, labels) in table) {
      val (on, off) = labels
      assertEquals(
        on,
        format(entity("binary_sensor.x", "on", "device_class" to deviceClass)),
        deviceClass,
      )
      assertEquals(
        off,
        format(entity("binary_sensor.x", "off", "device_class" to deviceClass)),
        deviceClass,
      )
    }
    assertEquals("On", format(entity("binary_sensor.x", "on")))
    assertEquals("Off", format(entity("binary_sensor.x", "off")))
    assertEquals(
      "Unavailable",
      format(entity("binary_sensor.x", "unavailable", "device_class" to "door")),
    )
    // The device class words are for binary sensors only: a switch stays On/Off.
    assertEquals("On", format(entity("switch.x", "on", "device_class" to "door")))
  }

  // C2 ----------------------------------------------------------------------------------------

  @Test
  fun onlyEnumStatesAreHumanised() {
    assertCases(
      listOf(
        entity("light.kitchen", "on") to "On",
        entity("lock.front", "locked") to "Locked",
        entity("alarm_control_panel.home", "armed_away") to "Armed away",
        entity("climate.lounge", "heat_cool") to "Heat/Cool",
        entity("person.yuri", "not_home") to "Away",
        entity("sensor.phase", "phase_2") to "Phase 2",
        entity("update.app", "V0.1.1") to "V0.1.1",
        entity("sensor.latest_release", "v0.1.1") to "v0.1.1",
        entity("sensor.last_commit", "refactor(data-history): split loader") to
          "refactor(data-history): split loader",
        entity("sensor.last_commit", "Refactor(data-history)…") to "Refactor(data-history)…",
        entity("sensor.song", "bohemian rhapsody") to "bohemian rhapsody",
        entity("sensor.code", "ABC_123") to "ABC_123",
        entity("sensor.nan", "NaN") to "NaN",
      )
    )
  }

  // C3 ----------------------------------------------------------------------------------------

  @Test
  fun unknownIsNotUnavailable() {
    assertEquals("Unknown", format(entity("sensor.x", "unknown")))
    assertEquals("Unavailable", format(entity("sensor.x", "unavailable")))
    assertEquals("Unavailable", format(null))
  }

  // C4 ----------------------------------------------------------------------------------------

  @Test
  fun precisionFollowsTheEntityThenTheDeviceClass() {
    assertCases(
      listOf(
        entity("sensor.t", "11.49", "unit_of_measurement" to "°C") to "11.5 °C",
        entity(
          "sensor.t",
          "11.49",
          "unit_of_measurement" to "°C",
          "device_class" to "temperature",
        ) to "11.5 °C",
        entity("sensor.t", "21", "unit_of_measurement" to "°C") to "21 °C",
        entity("sensor.t", "70.06", "unit_of_measurement" to "°F") to "70.1 °F",
        entity("sensor.h", "94.17", "unit_of_measurement" to "%", "device_class" to "humidity") to
          "94 %",
        entity("sensor.b", "57.5", "unit_of_measurement" to "%", "device_class" to "battery") to
          "58 %",
        entity("sensor.p", "1234.56", "unit_of_measurement" to "W", "device_class" to "power") to
          "1235 W",
        entity("sensor.p", "1.23456", "unit_of_measurement" to "kW", "device_class" to "power") to
          "1.23 kW",
        entity("sensor.e", "12.3456", "unit_of_measurement" to "kWh", "device_class" to "energy") to
          "12.35 kWh",
        entity("sensor.v", "231.47", "unit_of_measurement" to "V") to "231.5 V",
        entity("sensor.pr", "1013.25", "unit_of_measurement" to "hPa") to "1013 hPa",
        entity("sensor.x", "73.1299", "unit_of_measurement" to "Mbit/s") to "73.13 Mbit/s",
        // The entity's own precision wins, and pads like Home Assistant does.
        entity("sensor.t", "11.49", "unit_of_measurement" to "°C", "display_precision" to 2) to
          "11.49 °C",
        entity(
          "sensor.t",
          "21",
          "unit_of_measurement" to "°C",
          "suggested_display_precision" to 1,
        ) to "21.0 °C",
        entity(
          "sensor.t",
          "21.46",
          "unit_of_measurement" to "°C",
          "suggested_display_precision" to 0,
        ) to "21 °C",
      )
    )
  }

  @Test
  fun registryDisplayPrecisionWinsOverEverything() {
    val e =
      entity("sensor.t", "11.49", "unit_of_measurement" to "°C", "suggested_display_precision" to 1)
    assertEquals("11.490 °C", format(e, precision = 3))
    val snapshot =
      HaSnapshot(
        states = mapOf(e.entityId to e),
        devices = mapOf(e.entityId to DeviceInfo(displayPrecision = 2)),
      )
    assertEquals("11.49 °C", formatState(e, snapshot))
    // The live push reads the same snapshot, so it agrees with the bake.
    assertEquals(
      formatState(e, snapshot),
      cardSnapshotBindings(setOf(e.entityId), snapshot).strings["sensor.t.state"],
    )
  }

  // C5 ----------------------------------------------------------------------------------------

  @Test
  fun moneyReadsWithItsCurrencySymbol() {
    assertCases(
      listOf(
        entity("sensor.rate", "0.25", "unit_of_measurement" to "GBP/kWh") to "£0.25/kWh",
        entity("sensor.rate", "0.2534", "unit_of_measurement" to "GBP/kWh") to "£0.2534/kWh",
        entity("sensor.cost", "3.84", "unit_of_measurement" to "GBP") to "£3.84",
        entity(
          "sensor.cost",
          "3.8",
          "unit_of_measurement" to "GBP",
          "device_class" to "monetary",
        ) to "£3.80",
        entity("sensor.cost", "3.846", "unit_of_measurement" to "EUR") to "€3.85",
        entity("sensor.cost", "-1.5", "unit_of_measurement" to "USD") to "-$1.50",
        entity("sensor.cost", "1200.4", "unit_of_measurement" to "JPY") to "¥1200",
        entity("sensor.cost", "3.84", "unit_of_measurement" to "CHF") to "CHF 3.84",
        entity(
          "sensor.cost",
          "3.84",
          "unit_of_measurement" to "XYZ",
          "device_class" to "monetary",
        ) to "XYZ 3.84",
        // Not a known currency and not monetary: a plain unit.
        entity("sensor.aqi", "42", "unit_of_measurement" to "AQI") to "42 AQI",
      )
    )
  }

  // C6 ----------------------------------------------------------------------------------------

  @Test
  fun unitsReadNaturally() {
    assertCases(
      listOf(
        entity(
          "sensor.uv",
          "0",
          "unit_of_measurement" to "UV index",
          "friendly_name" to "UV index",
        ) to "0",
        entity(
          "sensor.uv",
          "3",
          "unit_of_measurement" to "UV index",
          "friendly_name" to "Garden UV index",
        ) to "3",
        entity(
          "sensor.uv",
          "3",
          "unit_of_measurement" to "UV index",
          "friendly_name" to "Garden UV",
        ) to "3 UV index",
        entity("sensor.vis", "10000", "unit_of_measurement" to "m", "device_class" to "distance") to
          "10 km",
        entity("sensor.vis", "12345", "unit_of_measurement" to "m") to "12.3 km",
        entity("sensor.vis", "950", "unit_of_measurement" to "m") to "950 m",
        entity("sensor.vis", "1e4", "unit_of_measurement" to "m") to "10 km",
        entity(
          "sensor.wind",
          "50",
          "unit_of_measurement" to "°",
          "device_class" to "wind_direction",
        ) to "NE 50°",
        entity(
          "sensor.wind_bearing",
          "200.4",
          "unit_of_measurement" to "°",
          "friendly_name" to "Wind bearing",
        ) to "SSW 200°",
        entity(
          "sensor.wind",
          "359",
          "unit_of_measurement" to "°",
          "device_class" to "wind_direction",
        ) to "N 359°",
        entity(
          "sensor.sun_elevation",
          "12.5",
          "unit_of_measurement" to "°",
          "friendly_name" to "Sun elevation",
        ) to "12.5°",
      )
    )
  }

  @Test
  fun compassPointsCoverTheCircle() {
    val cases =
      mapOf(
        0.0 to "N",
        11.0 to "N",
        12.0 to "NNE",
        45.0 to "NE",
        90.0 to "E",
        180.0 to "S",
        270.0 to "W",
        349.0 to "N",
        -90.0 to "W",
        720.0 to "N",
      )
    cases.forEach { (deg, point) -> assertEquals(point, compassPoint(deg), "$deg") }
  }

  // C7 ----------------------------------------------------------------------------------------

  @Test
  fun durationsReadAsHoursMinutesSeconds() {
    val d = "device_class" to "duration"
    assertCases(
      listOf(
        entity("sensor.d", "65", "unit_of_measurement" to "min", d) to "1 h 05 min",
        entity("sensor.d", "5", "unit_of_measurement" to "min", d) to "5 min",
        entity("sensor.d", "45", "unit_of_measurement" to "s", d) to "45 s",
        entity("sensor.d", "330", "unit_of_measurement" to "s", d) to "5 min 30 s",
        entity("sensor.d", "3600", "unit_of_measurement" to "s", d) to "1 h",
        entity("sensor.d", "1.5", "unit_of_measurement" to "h", d) to "1 h 30 min",
        entity("sensor.d", "2.125", "unit_of_measurement" to "d", d) to "2 d 3 h",
        entity("sensor.d", "1", "unit_of_measurement" to "d", d) to "1 d",
        entity("sensor.d", "1500", "unit_of_measurement" to "ms", d) to "2 s",
        entity("sensor.d", "0", "unit_of_measurement" to "min", d) to "0 s",
        entity("sensor.d", "90", d) to "1 min 30 s",
        entity("sensor.d", "-5", "unit_of_measurement" to "min", d) to NO_VALUE,
      )
    )
  }

  // C8 ----------------------------------------------------------------------------------------

  @Test
  fun redactedValuesAreNeverInvented() {
    assertCases(
      listOf(
        entity("sensor.cost", "0.xx", "unit_of_measurement" to "GBP") to NO_VALUE,
        entity("sensor.speed", "551.XXXXXXXX", "unit_of_measurement" to "Mbit/s") to NO_VALUE,
        entity("sensor.t", "-3.xx", "unit_of_measurement" to "°C") to NO_VALUE,
      )
    )
    assertEquals(NO_VALUE, formatValueWithUnit("21.xx", "°C"))
  }

  // C11 ---------------------------------------------------------------------------------------

  @Test
  fun countUnitsAgreeWithTheirNumber() {
    assertCases(
      listOf(
        entity("sensor.stars", "1", "unit_of_measurement" to "stars") to "1 star",
        entity("sensor.stars", "2", "unit_of_measurement" to "stars") to "2 stars",
        entity("sensor.stars", "0", "unit_of_measurement" to "stars") to "0 stars",
        entity("sensor.issues", "1", "unit_of_measurement" to "issues") to "1 issue",
        entity("sensor.issues", "3", "unit_of_measurement" to "issue") to "3 issues",
        entity("sensor.ents", "1", "unit_of_measurement" to "entities") to "1 entity",
        entity("sensor.ents", "1.0", "unit_of_measurement" to "items") to "1 item",
        entity("sensor.pr", "1", "unit_of_measurement" to "pull requests") to "1 pull request",
        // Not a count unit: untouched.
        entity("sensor.x", "1", "unit_of_measurement" to "kWh") to "1 kWh",
        entity("sensor.x", "1", "unit_of_measurement" to "Stars") to "1 Stars",
      )
    )
  }

  // Shared helpers ----------------------------------------------------------------------------

  @Test
  fun formatValueWithUnitUsesTheSameRules() {
    assertEquals("21.5 °C", formatValueWithUnit("21.46", "°C"))
    assertEquals("64 %", formatValueWithUnit("63.5", "%"))
    assertEquals("50°", formatValueWithUnit("50", "°"))
    assertEquals("Calm km/h", formatValueWithUnit("Calm", "km/h"))
    assertEquals("21.46 °C", formatValueWithUnit("21.46", "°C", displayPrecision = 2))
  }

  @Test
  fun formatDecimalRoundsHalfUpWithoutFloatSkew() {
    assertEquals("1.01", formatDecimal("1.005", 0, 2))
    assertEquals("0.3", formatDecimal("0.25", 0, 1))
    assertEquals("-0.3", formatDecimal("-0.25", 0, 1))
    assertEquals("0", formatDecimal("-0.04", 0, 1))
    assertEquals("2.50", formatDecimal("2.5", 2, 2))
    assertEquals("100", formatDecimal("99.95", 0, 1))
    assertEquals("12", formatDecimal("1.2e1", 0, 2))
  }

  @Test
  fun sameStringForBakeAndPushAcrossEveryCase() {
    val e = entity("event.motion", "2026-04-20T12:22:53.298+00:00")
    val snapshot = HaSnapshot(states = mapOf(e.entityId to e))
    assertEquals(
      formatState(e, snapshot),
      cardSnapshotBindings(setOf(e.entityId), snapshot).strings["event.motion.state"],
    )
    assertNotEquals("2026-04-20T12:22:53.298+00:00", formatState(e, snapshot))
  }
}
