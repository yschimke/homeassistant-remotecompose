package ee.schimke.ha.rc.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HaControlSlotsTest {
  @Test
  fun aSlotTakesOnlyItsKindOfRenderer() {
    assertFailsWith<IllegalArgumentException> {
      HaComponentSlots.None.with(HaComponentSlot.Switch, GrammarRenderers.Arc)
    }
    assertFailsWith<IllegalArgumentException> {
      HaComponentSlots.None.with(HaComponentSlot.ArcMeter, TokenSwitch)
    }
    assertFailsWith<IllegalArgumentException> {
      HaComponentSlots.None.with(HaComponentSlot.Key, TokenSwitch)
    }
  }

  @Test
  fun onlyFamiliesWithTheirOwnControlsNameThem() {
    // Every other bespoke family draws the token switch and keys with no mount: unchanged.
    for (family in
      listOf(
        HaVisualFamily.Classic,
        HaVisualFamily.HomeAssistant,
        HaVisualFamily.Trinitron,
        HaVisualFamily.Lcd,
        HaVisualFamily.Braun,
        HaVisualFamily.Nixie,
      )) {
      assertNull(familySwitch(family), "$family switch")
      assertNull(familyKey(family), "$family key")
    }
    assertSame(FamilyControls.ExpressiveSwitch, familySwitch(HaVisualFamily.Expressive))
    assertSame(FamilyControls.IkeaSwitch, familySwitch(HaVisualFamily.Ikea))
    assertSame(FamilyControls.ConsoleSwitch, familySwitch(HaVisualFamily.Console))
    HaVisualFamily.entries
      .filter { it.isCommunity }
      .forEach { assertSame(FamilyControls.Community, familySwitch(it), "$it") }
    assertSame(FamilyControls.ConsoleKey, familyKey(HaVisualFamily.Console))
    assertSame(FamilyControls.IkeaKey, familyKey(HaVisualFamily.Ikea))
  }

  @Test
  fun kitsDrawTheSharedControlsUnlessTheyPickOne() {
    for (kit in HaUberTheme.kits) {
      assertSame(GrammarKey, kit.components.key, kit.id)
      val expected = if (kit == HaUberTheme.Aurora) FamilyControls.ExpressiveSwitch else TokenSwitch
      assertSame(expected, kit.components.switch, kit.id)
    }
  }

  @Test
  fun cardsAskForControlsByRoleNotFamily() {
    // The switch and key mount were family branches in these files; they resolve through slots now.
    val dir = File("src/commonMain/kotlin/ee/schimke/ha/rc/components")
    for (name in listOf("RemoteHaEntityRow.kt", "ConsoleSurfaces.kt")) {
      assertFalse(File(dir, name).readText().contains("HaVisualFamily."), name)
    }
    assertTrue(dir.listFiles()!!.none { it.readText().contains("consoleKeyChrome") })
    assertEquals(
      setOf(HaComponentSlot.Switch, HaComponentSlot.Key),
      HaComponentSlot.entries
        .filter { slot -> HaComponentSlots.Grammar.visual(slot) == null }
        .toSet(),
    )
  }

  @Test
  fun everyAcceptedSegmentCountLeavesCellsOfPositiveWidth() {
    for (n in 2..48) {
      val cell = (1f - segmentGapShare(n) * (n - 1)) / n
      assertTrue(cell > 0f, "$n segments")
      HaComponentGrammar(level = HaLevelStyle.Segmented, segments = n)
    }
  }
}
