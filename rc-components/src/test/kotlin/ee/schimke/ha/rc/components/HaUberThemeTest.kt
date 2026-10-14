package ee.schimke.ha.rc.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HaUberThemeTest {
  @Test
  fun idsAreUniqueAndResolve() {
    val ids = HaUberTheme.all.map { it.id }
    assertEquals(ids.toSet().size, ids.size)
    HaUberTheme.all.forEach { assertEquals(it, HaUberTheme.byId(it.id.uppercase())) }
    assertEquals(HaUberTheme.Classic, HaUberTheme.byId("nonsense"))
  }

  @Test
  fun classicIsTheCardsOwnLook() {
    val classic = HaUberTheme.Classic
    assertEquals(HaTheme.Light, classic.palette(darkTheme = false))
    assertEquals(HaTheme.Dark, classic.palette(darkTheme = true))
    assertEquals(HaCardShapeStyle(cornerDp = 12, borderDp = 1), classic.shape)
    assertEquals(HaMeterStyle(), classic.meter)
    assertNull(classic.inkFor(darkTheme = false))
  }

  @Test
  fun singleModeThemesFallBack() {
    assertTrue(HaUberTheme.Nixie.palette(darkTheme = false).isDark)
    assertTrue(HaUberTheme.Trinitron.palette(darkTheme = false).isDark)
  }

  @Test
  fun everyPaletteMatchesItsMode() {
    for (theme in HaUberTheme.all) {
      theme.light?.let { assertEquals(false, it.isDark, "${theme.id} light") }
      theme.dark?.let { assertEquals(true, it.isDark, "${theme.id} dark") }
    }
  }

  @Test
  fun lcdInksPerMode() {
    assertEquals(HaUberTheme.Lcd.light!!.primaryText, HaUberTheme.Lcd.inkFor(darkTheme = false))
    assertEquals(HaUberTheme.Lcd.dark!!.primaryText, HaUberTheme.Lcd.inkFor(darkTheme = true))
  }
}
