package ee.schimke.ha.rc.icons.mdi

import io.github.timoptr.mdiicons.Mdi
import io.github.timoptr.mdiicons.generated.HomeAssistant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MdiIconsTest {
  @Test
  fun namesFollowHomeAssistantPrefixes() {
    assertEquals("water-percent", MdiIcons.nameOf("mdi:water-percent"))
    assertEquals("fan", MdiIcons.nameOf(" hass:Fan "))
    assertEquals("weather-sunny", MdiIcons.nameOf("weather-sunny"))
    assertNull(MdiIcons.nameOf("phu:rollershutter"))
    assertNull(MdiIcons.nameOf("mdi:"))
    assertNull(MdiIcons.nameOf("  "))
    assertNull(MdiIcons.nameOf(null))
  }

  @Test
  fun resolvesTheWholeCatalog() {
    // Beyond the 776 icons the old generated subset carried.
    for (name in listOf("home-assistant", "home-floor-l", "unicorn", "zodiac-aquarius")) {
      assertTrue(name in MdiIcons, name)
      val glyph = assertNotNull(MdiIcons[name], name)
      assertEquals("mdi:$name", glyph.name)
      assertEquals(24f, glyph.viewportWidth)
      assertTrue(glyph.root.size > 0, name)
    }
    assertFalse("not-an-mdi-icon" in MdiIcons)
    assertNull(MdiIcons["not-an-mdi-icon"])
  }

  @Test
  fun followsHistoricalRenames() {
    // Renamed in MDI 5; older Home Assistant configurations still name it.
    assertTrue("account-card-details" in MdiIcons)
    assertNotNull(MdiIcons["account-card-details"])
  }

  @Test
  fun cachesGlyphsUntilReleased() {
    val first = MdiIcons["lightbulb"]
    assertSame(first, MdiIcons["lightbulb"])
    assertSame(MdiIcons[Mdi.HomeAssistant], MdiIcons["home-assistant"])
    MdiIcons.releaseMemory()
    val rebuilt = MdiIcons["lightbulb"]
    assertTrue(first !== rebuilt)
    assertEquals(first, rebuilt)
  }
}
