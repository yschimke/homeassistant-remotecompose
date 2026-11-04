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
    assertFailsWith<IllegalArgumentException> {
      HaComponentSlots.None.with(HaComponentSlot.DialRing, GrammarRenderers.Arc)
    }
    assertFailsWith<IllegalArgumentException> {
      HaComponentSlots.None.with(HaComponentSlot.IconChip, TokenTransportKey)
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
      val aurora = kit == HaUberTheme.Aurora
      assertSame(
        if (aurora) FamilyControls.ExpressiveSwitch else TokenSwitch,
        kit.components.switch,
      )
      assertSame(
        if (aurora) FamilyControls.ExpressiveIconChip else TokenIconChip,
        kit.components.iconChip,
        kit.id,
      )
      assertSame(GrammarRenderers.Dial, kit.components.dial, kit.id)
      assertSame(StandardDialLayout, kit.components.dialLayout, kit.id)
      assertSame(TokenTransportKey, kit.components.transportKey, kit.id)
      assertSame(GrammarWearContainer, kit.wearContainer(), kit.id)
    }
  }

  @Test
  fun dialTransportAndChipFamiliesAreTheOnesThatHadTheirOwn() {
    // Families with no dial body, transport key or chip of their own take the standard / token
    // one: unchanged, since those are the code they used to fall through to.
    for (family in
      listOf(
        HaVisualFamily.Classic,
        HaVisualFamily.HomeAssistant,
        HaVisualFamily.Trinitron,
        HaVisualFamily.Lcd,
        HaVisualFamily.Braun,
        HaVisualFamily.Nixie,
        HaVisualFamily.Mushroom,
      )) {
      assertNull(familyDialLayout(family), "$family dial layout")
      assertNull(familyTransportKey(family), "$family transport")
      assertNull(familyIconChip(family), "$family chip")
    }
    val console = FamilyControls.ConsoleDialLayout
    assertEquals(HaDialBody.ConsoleThermostat, console.body("current_temperature_label"))
    assertEquals(HaDialBody.Ring, console.body("brightness_pct"))
    assertEquals(HaDialBody.IkeaLight, FamilyControls.IkeaDialLayout.body("brightness_pct"))
    assertEquals(HaDialBody.Ring, FamilyControls.IkeaDialLayout.body("current_temperature_label"))
    assertEquals(HaDialBody.CupertinoBar, familyDialLayout(HaVisualFamily.Cupertino)!!.body(null))
    // Steppers beside the dial: exactly the four families that had them there.
    assertEquals(
      setOf(
        HaVisualFamily.Bubble,
        HaVisualFamily.Cupertino,
        HaVisualFamily.Lcars,
        HaVisualFamily.Aeon,
      ),
      HaVisualFamily.entries.filter { familyDialLayout(it)?.sideSteppers == true }.toSet(),
    )
    assertSame(FamilyControls.ConsoleTransportKey, familyTransportKey(HaVisualFamily.Console))
    assertSame(FamilyControls.ExpressiveIconChip, familyIconChip(HaVisualFamily.Expressive))
    assertTrue(FamilyControls.ExpressiveIconChip.pressable)
    assertFalse(TokenIconChip.pressable)
  }

  @Test
  fun cardsAskByRoleNotFamily() {
    // No card names a style family: every card file (RemoteHa*) and the card chrome ask the active
    // theme's slots and layouts. Families are named only in the registries — the theme
    // definitions, the family tables (HaControls, HaLayouts, FamilyVisuals, the Wear container) and
    // each family's own drawings (Meters<Family>.kt, Dials.kt, HistoryTraces.kt, ...).
    val dir = File("src/commonMain/kotlin/ee/schimke/ha/rc/components")
    val cards =
      dir.listFiles()!!.filter {
        it.name.startsWith("RemoteHa") ||
          it.name in setOf("CardChrome.kt", "UnavailableFrame.kt", "ConsoleSurfaces.kt")
      }
    assertTrue(cards.size > 40, "found the card files")
    for (file in cards) {
      val text = file.readText()
      for (name in listOf("HaVisualFamily.", "visualFamily()", "isCommunity")) {
        assertFalse(text.contains(name), "${file.name} names a family ($name)")
      }
    }
    assertTrue(dir.listFiles()!!.none { it.readText().contains("consoleKeyChrome") })
  }

  @Test
  fun everySlotTakesOneKindAndKitsFillAllButLayouts() {
    // Every slot but Layouts has a shared drawing in the grammar set; Layouts is inherited.
    assertEquals(
      HaComponentSlot.entries.toSet() - HaComponentSlot.Layouts,
      HaComponentSlots.Grammar.slots,
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
