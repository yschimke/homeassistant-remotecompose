package ee.schimke.ha.designs

import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DeviceMatch
import ee.schimke.ha.rc.CommonCaptureHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device packs in the card design library ([DevicePackLibrary]): each pack's controls are a
 * library design of their own, keyed as a tile of the pack's entity in every theme, drawn with the
 * card's own entity as slot 0, and suggested to the pack's class.
 */
class DevicePackLibraryTest {
  private val host = CommonCaptureHost(LibraryCaptureProfile.OPERATIONS)

  @Test
  fun publishesEachPacksControls() {
    assertEquals(
      listOf(
        "garage-door-controls-material3",
        "garage-door-controls-skeuomorphic",
        "garage-door-controls-overlay",
        "thermostat-controls-material3",
        "thermostat-controls-skeuomorphic",
        "thermostat-controls-overlay",
      ),
      DevicePackLibrary.published.map { it.designId },
    )
  }

  @Test
  fun aGarageDesignIsForEveryGarageDoorAndAThermostatIsChosenNotSuggested() {
    val garage = DevicePackLibrary.published.first { it.pack == "garage-door" }
    val thermostat = DevicePackLibrary.published.first { it.pack == "thermostat" }
    assertEquals(DeviceMatch(deviceClass = "garage"), DevicePackLibrary.device(garage))
    assertNull(DevicePackLibrary.device(thermostat))
    assertEquals("pack:garage-door-controls-material3", DevicePackLibrary.designId(garage))
  }

  @Test
  fun eachDesignIsKeyedByItsOwnVariantInEveryThemeOfItsTile() {
    for (design in DevicePackLibrary.published) {
      val keys = DevicePackLibrary.keys(design)
      val tile =
        DevicePackLibrary.example(design).let { CardDesignLibrary.keysOf(it.card, it.snapshot) }
      assertEquals(tile.size, keys.size, design.designId)
      assertTrue(keys.isNotEmpty(), design.designId)
      assertTrue(
        keys.all {
          it.cardType == DevicePackLibrary.cardType(design) &&
            it.variant == DevicePackLibrary.variant(design)
        },
      )
      assertTrue(
        keys.none { it.variant == DesignKey.DEFAULT_VARIANT },
        "never the tile's own design",
      )
      assertEquals(design, DevicePackLibrary.designFor(keys.first()))
    }
  }

  @Test
  fun capturesTheDesignBoundToTheCardsEntityWithItsIdAndTitle() {
    val skeuomorphic =
      DevicePackLibrary.published.first { it.designId == "garage-door-controls-skeuomorphic" }
    val key = DevicePackLibrary.keys(skeuomorphic).first()
    val built = DevicePackLibrary.capture(host, key, skeuomorphic)
    assertEquals(1, built.slots)
    assertEquals("pack:garage-door-controls-skeuomorphic", built.designId)
    assertEquals("Garage door · Wall console", built.title)
    assertEquals(DeviceMatch(deviceClass = "garage"), built.device)
    val text = String(built.bytes, Charsets.ISO_8859_1)
    // The door follows the card's own cover, not the pack's example entity.
    assertTrue("cover.s0.current_position" in text, "binds the slot's position")
    assertTrue("cover.garage_door" !in text, "no example entity left")
  }
}
