package ee.schimke.ha.rc.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HaUberThemeTest {
  @Test
  fun homeAssistantUsesBuiltInTokensInBothModes() {
    val theme = HaUberTheme.byId("HOME-ASSISTANT")
    assertEquals(HaUberTheme.HomeAssistant, theme)
    assertEquals(HaVisualFamily.HomeAssistant, theme.visuals)
    assertEquals(HaCardShapeStyle(cornerDp = 12, borderDp = 1), theme.shape)
    assertEquals(
      ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight.Normal,
      theme.valueWeight,
    )
    assertEquals(androidx.compose.ui.graphics.Color(0xFF5E5E5E), theme.light!!.secondaryText)
    assertEquals(androidx.compose.ui.graphics.Color(0xFF9B9B9B), theme.dark!!.secondaryText)
    assertEquals(androidx.compose.ui.graphics.Color(0xFF009AC7), theme.light!!.linkText)
    assertEquals(theme.light!!.linkText, theme.dark!!.linkText)
    for (dark in listOf(false, true)) {
      val palette = theme.palette(dark)
      assertTrue(contrast(palette.primaryText, palette.cardBackground) >= 4.5f)
      assertTrue(contrast(palette.secondaryText, palette.cardBackground) >= 4.5f)
      assertEquals(palette.dashboardBackground, palette.sectionBackground)
      assertNull(theme.inkFor(dark))
    }
  }

  @Test
  fun idsAreUniqueAndResolve() {
    val ids = HaUberTheme.all.map { it.id }
    assertEquals(ids.toSet().size, ids.size)
    HaUberTheme.all.forEach { assertEquals(it, HaUberTheme.byId(it.id.uppercase())) }
    assertEquals(HaUberTheme.Classic, HaUberTheme.byId("nonsense"))
  }

  @Test
  fun ikeaHasAccessiblePaperAndCharcoalPalettes() {
    val ikea = HaUberTheme.byId("ikea")
    assertEquals(HaUberTheme.Ikea, ikea)
    assertEquals(HaVisualFamily.Ikea, ikea.visuals)
    assertEquals(HaSurfaceMaterial.BirchFrame, ikea.shape.material)
    for (dark in listOf(false, true)) {
      val palette = ikea.palette(dark)
      assertTrue(contrast(palette.primaryText, palette.cardBackground) >= 4.5f)
      assertTrue(contrast(palette.secondaryText, palette.cardBackground) >= 4.5f)
      assertTrue(contrast(palette.linkText, palette.cardBackground) >= 4.5f)
      assertTrue(contrast(palette.primaryText, palette.placeholderBackground) >= 4.5f)
    }
    assertNull(ikea.ink, "Warnings and readings retain their semantic colours")
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
  fun everyBespokeThemeDrawsItsOwnVisuals() {
    // Each bespoke theme is its own design, not a restyle of another's.
    val families = HaUberTheme.bespoke.map { it.visuals }
    assertEquals(families.toSet().size, families.size)
  }

  @Test
  fun everyKitExtendsABespokeThemeAndDrawsThroughItsGrammar() {
    // A kit shares its base's family for what isn't drawn through slots yet, and draws every slot
    // from its own grammar — so it is a look of its own without a Meters<Family>.kt.
    assertTrue(HaUberTheme.kits.isNotEmpty())
    for (kit in HaUberTheme.kits) {
      val base = HaUberTheme.bespoke.single { it.id == kit.extends }
      assertEquals(base.visuals, kit.visuals, kit.id)
      // Every slot but Layouts, which a kit inherits from the style it extends unless it picks its
      // own.
      assertEquals(
        HaComponentSlot.entries.toSet() -
          if (kit == HaUberTheme.RuggedDigital) emptySet() else setOf(HaComponentSlot.Layouts),
        kit.components.slots,
        kit.id,
      )
      assertTrue(kit.grammar != base.grammar || kit.palette(true) != base.palette(true), kit.id)
    }
    // No two kits are the same tokens.
    assertEquals(HaUberTheme.kits.size, HaUberTheme.kits.map { it.grammar }.toSet().size)
  }

  @Test
  fun kitPalettesAreLegible() {
    for (kit in HaUberTheme.kits) for (dark in listOf(false, true)) {
      val palette = kit.palette(dark)
      assertTrue(contrast(palette.primaryText, palette.cardBackground) >= 4.5f, "${kit.id} text")
      assertTrue(
        contrast(palette.secondaryText, palette.cardBackground) >= 4.5f,
        "${kit.id} secondary",
      )
      assertTrue(contrast(palette.linkText, palette.cardBackground) >= 3f, "${kit.id} accent")
    }
  }

  @Test
  fun bespokeThemesKeepTheirOwnDrawings() {
    // Bespoke themes draw every older visual by hand; only new slots fall back to the grammar.
    for (theme in HaUberTheme.bespoke) assertEquals(HaComponentSlots.None, theme.components)
    assertEquals(HaComponentGrammar.Default, HaUberTheme.Classic.grammar)
    assertEquals(HaLevelStyle.Segmented, HaUberTheme.Lcd.grammar.level)
    assertEquals(HaLevelStyle.Pointer, HaUberTheme.Braun.grammar.level)
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

  @Test
  fun accentsAreLegibleOnStyledCards() {
    val green = androidx.compose.ui.graphics.Color(0xFF43A047)
    val console = HaActiveUberTheme(HaUberTheme.Console, darkTheme = true)
    val surface = HaUberTheme.Console.palette(darkTheme = true).cardBackground
    assertTrue(contrast(green, surface) < 3f, "the fixture starts illegible")
    assertTrue(contrast(legibleOn(console, green), surface) >= 3f)
    // Classic keeps its own colours, and a legible accent is left alone.
    assertEquals(green, legibleOn(HaActiveUberTheme(HaUberTheme.Classic, false), green))
    // Style only (Wear): the host's surface, not Console's, so the accent is left alone.
    assertEquals(
      green,
      legibleOn(HaActiveUberTheme(HaUberTheme.Console, true, ownPalette = false), green),
    )
    val white = androidx.compose.ui.graphics.Color.White
    assertEquals(white, legibleOn(console, white))
  }

  @Test
  fun communityFamiliesHaveTwoAccessibleModesAndDistinctDesigns() {
    val ids =
      setOf(
        "mushroom",
        "bubble",
        "minimalist",
        "cupertino",
        "catppuccin",
        "obsidian",
        "noctis",
        "lcars",
        "arctic",
        "aeon",
      )
    val families = HaUberTheme.all.filter { it.id in ids }
    assertEquals(ids, families.map { it.id }.toSet())
    assertEquals(10, families.map { it.visuals }.toSet().size)
    families.forEach { theme ->
      assertTrue(theme.description.isNotBlank(), theme.id)
      assertTrue(theme.light != null && theme.dark != null, theme.id)
      for (dark in listOf(false, true)) {
        val palette = theme.palette(dark)
        assertTrue(
          contrast(palette.primaryText, palette.cardBackground) >= 4.5f,
          "${theme.id}: primary",
        )
        assertTrue(
          contrast(palette.secondaryText, palette.cardBackground) >= 4.5f,
          "${theme.id}: secondary",
        )
        assertTrue(contrast(palette.linkText, palette.cardBackground) >= 4.5f, "${theme.id}: link")
        val green = androidx.compose.ui.graphics.Color(0xFF43A047)
        assertTrue(
          contrast(legibleOn(HaActiveUberTheme(theme, dark), green), palette.cardBackground) >=
            4.5f,
          "${theme.id}: status text remains legible",
        )
        assertNull(theme.inkFor(dark), "${theme.id}: preserve semantic hot/low colours")
      }
    }
  }
}
