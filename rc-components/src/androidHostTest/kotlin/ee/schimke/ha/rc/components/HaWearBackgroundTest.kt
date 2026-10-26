package ee.schimke.ha.rc.components

import androidx.compose.ui.graphics.lerp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HaWearBackgroundTest {
  @Test
  fun everyStyleButClassicBringsItsOwnContainer() {
    for (style in HaUberTheme.all) {
      val background = style.wearBackground()
      if (style == HaUberTheme.Classic) assertNull(background)
      else assertEquals(style.palette(darkTheme = true).cardBackground, background?.base, style.id)
    }
  }

  @Test
  fun everyStyleLooksDifferentOnTheWatch() {
    val looks = HaUberTheme.all.map { it.wearBackground() to it.wearLevelLook() }
    assertEquals(HaUberTheme.all.size, looks.toSet().size)
  }

  @Test
  fun aFillLevelKeepsItsStylesTextReadable() {
    for (style in HaUberTheme.all) {
      val look = style.wearLevelLook()
      // A ring lies in the container's padding, outside the text.
      if (look.ringDp > 0f) continue
      val palette = style.palette(darkTheme = true)
      val filled = lerp(style.wearBackground()!!.base, look.color, look.alpha)
      assertTrue(contrast(palette.primaryText, filled) >= 4.5f, "${style.id} primary text")
      assertTrue(contrast(palette.secondaryText, filled) >= 3f, "${style.id} secondary text")
      assertTrue(look.alpha > 0f, "${style.id} fill shows")
    }
  }

  @Test
  fun levelsAreBatteriesHumidityAndPercentages() {
    assertEquals(
      HaMeasureKind.Battery,
      HaWearLevel.of("sensor.phone", "battery", "%", 40f)?.kind,
    )
    assertEquals(true, HaWearLevel.of("sensor.bath", "humidity", "%", 60f)?.vertical)
    assertEquals(false, HaWearLevel.of("sensor.cpu", null, "%", 12f)?.vertical)
    // Not sensors, or not a reading on a 0–100 scale of its own: no level.
    assertNull(HaWearLevel.of("sensor.out", "temperature", "°C", 4f))
    assertNull(HaWearLevel.of("light.kitchen", null, "%", 50f))
    assertNull(HaWearLevel.of("sensor.power", "power", "W", 900f))
    assertNull(HaWearLevel.of("sensor.text", null, null, Float.NaN))
  }
}
