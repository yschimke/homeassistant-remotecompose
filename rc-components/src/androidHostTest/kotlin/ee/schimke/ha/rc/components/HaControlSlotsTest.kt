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
  fun cardsAskForControlsByRoleNotFamily() {
    // The switch and key mount were family branches in these files; they resolve through slots now.
    val dir = File("src/commonMain/kotlin/ee/schimke/ha/rc/components")
    for (name in listOf("RemoteHaEntityRow.kt", "ConsoleSurfaces.kt", "RemoteHaButton.kt")) {
      assertFalse(File(dir, name).readText().contains("HaVisualFamily."), name)
    }
    // The dial card names no family for its layout or body — only its ring's fallback table
    // (Classic's arc, Console's knob, the rest in Dials.kt) — and the media card none for its keys.
    val dial = File(dir, "RemoteHaArcDial.kt").readText()
    for (family in listOf("Cupertino", "Ikea", "Bubble", "Lcars", "Aeon")) {
      assertFalse(dial.contains("HaVisualFamily.$family"), "dial names $family")
    }
    assertFalse(dial.contains("isCommunity"))
    assertFalse(File(dir, "RemoteHaMediaControl.kt").readText().contains("ikeaKeyChrome"))
    assertTrue(dir.listFiles()!!.none { it.readText().contains("consoleKeyChrome") })
    assertEquals(
      setOf(
        HaComponentSlot.Switch,
        HaComponentSlot.Key,
        HaComponentSlot.DialRing,
        HaComponentSlot.DialLayout,
        HaComponentSlot.TransportKey,
        HaComponentSlot.IconChip,
      ),
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
