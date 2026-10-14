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
    assertEquals(HaVisualFamily.Classic, classic.visuals)
    assertEquals(HaReadoutStyle.Plain, classic.readout)
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

  @Test
  fun everyThemeDrawsItsOwnVisuals() {
    // Each uber theme is its own design, not a restyle of another's.
    val families = HaUberTheme.all.map { it.visuals }
    assertEquals(families.toSet().size, families.size)
  }

  @Test
  fun lcdHasAHotInkPerMode() {
    assertTrue(HaUberTheme.Lcd.hotInkFor(darkTheme = false) != null)
    assertTrue(HaUberTheme.Lcd.hotInkFor(darkTheme = true) != null)
    assertNull(HaUberTheme.Classic.hotInkFor(darkTheme = false))
  }

  @Test
  fun onlyConsoleIsPaintedMetal() {
    // The skeuomorphic material is the Console's alone; every other theme stays flat.
    val metal = HaUberTheme.all.filter { it.shape.material == HaSurfaceMaterial.PaintedMetal }
    assertEquals(listOf(HaUberTheme.Console), metal)
    assertEquals(HaVisualFamily.Console, HaUberTheme.Console.visuals)
  }
}
