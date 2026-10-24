package ee.schimke.ha.rc.components

import kotlin.test.Test
import kotlin.test.assertEquals

class HaReadingTest {
  @Test
  fun deviceClassPicksTheKind() {
    assertEquals(HaMeasureKind.Temperature, HaMeasureKind.of("temperature", "°C"))
    assertEquals(HaMeasureKind.Battery, HaMeasureKind.of("battery", "%"))
    assertEquals(HaMeasureKind.Humidity, HaMeasureKind.of("moisture", "%"))
    assertEquals(HaMeasureKind.Power, HaMeasureKind.of("energy", "kWh"))
  }

  @Test
  fun unitPicksTheKindWithoutADeviceClass() {
    assertEquals(HaMeasureKind.Temperature, HaMeasureKind.of(null, "°F"))
    assertEquals(HaMeasureKind.Percentage, HaMeasureKind.of(null, "%"))
    assertEquals(HaMeasureKind.Illuminance, HaMeasureKind.of(null, "lx"))
    assertEquals(HaMeasureKind.Generic, HaMeasureKind.of(null, "ppm"))
    assertEquals(HaMeasureKind.Generic, HaMeasureKind.of(null, null))
  }

  @Test
  fun eachKindHasItsVisual() {
    assertEquals(HaMeasureVisual.Thermometer, HaMeasureKind.Temperature.visual)
    assertEquals(HaMeasureVisual.Battery, HaMeasureKind.Battery.visual)
    assertEquals(HaMeasureVisual.Bar, HaMeasureKind.Percentage.visual)
  }

  @Test
  fun temperatureScaleFollowsTheUnit() {
    assertEquals(-10.0..40.0, HaMeasureKind.Temperature.defaultRange("°C"))
    assertEquals(14.0..104.0, HaMeasureKind.Temperature.defaultRange("°F"))
  }

  @Test
  fun temperatureRampConvertsToFahrenheit() {
    val celsius = HaMeasureKind.Temperature.colorRule("°C") as HaMeterColor.Ramp
    val fahrenheit = HaMeasureKind.Temperature.colorRule("°F") as HaMeterColor.Ramp
    assertEquals(listOf(10f, 18f, 25f, 30f), celsius.stops.map { it.first })
    assertEquals(listOf(50f, 64.4f, 77f, 86f), fahrenheit.stops.map { it.first })
    assertEquals(celsius.stops.map { it.second }, fahrenheit.stops.map { it.second })
  }

  @Test
  fun readingsAreSpelledLikeHomeAssistant() {
    assertEquals("21.5 °C", HaReading.formatReading(21.5f, "°C"))
    assertEquals("46 %", HaReading.formatReading(46f, "%"))
    assertEquals("12", HaReading.formatReading(12f, null))
  }

  @Test
  fun presentationsParseLeniently() {
    assertEquals(HaPresentation.Expanded, HaPresentation.parse(" Expanded "))
    assertEquals(HaPresentation.Standard, HaPresentation.parse("nonsense"))
    assertEquals(HaMeasureKind.Generic, HaMeasureKind.parse("nonsense"))
  }

  @Test
  fun zonesMarkTheHotEndAndTheCriticalLevel() {
    val hot = HaReading.static("t", 20f, "°C", HaMeasureKind.Temperature).zones()
    // 30 °C on the default −10…40 °C scale.
    assertEquals(0.8f, hot.hotFrom!!, 0.0001f)
    val low = HaReading.static("b", 50f, "%", HaMeasureKind.Battery).zones()
    assertEquals(0.15f, low.lowBelow!!, 0.0001f)
    assertEquals(HaMeterZones(), HaReading.static("g", 1f, null).zones())
  }
}
