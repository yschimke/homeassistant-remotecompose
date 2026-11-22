package ee.schimke.ha.designs

import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DeviceMatch
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CommonCaptureHost
import ee.schimke.ha.rc.SystemHaClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

  /**
   * Every value [bytes] declares that a host must push: not a colour, its own metadata or the
   * display's.
   */
  private fun reads(bytes: ByteArray): Set<String> =
    RcDocumentCodec.decode(bytes, RcWireLimits())
      .operations
      .filterIsInstance<RcNamedVariable>()
      .filter { it.type != RcNamedVariable.COLOR_TYPE }
      .map { it.name.removePrefix("USER:") }
      .filterNot { it.startsWith("meta.") || it.startsWith("host.") }
      .toSet()

  @Test
  fun everyValueAPackReadsIsPushedAndFollowsTheEntity() {
    for (design in DevicePackLibrary.published) {
      val example = DevicePackLibrary.example(design)
      val bytes =
        DevicePackLibrary.capture(host, DevicePackLibrary.keys(design).first(), design).bytes
      val entity = example.snapshot.states.keys.single()
      val live =
        example.snapshot.copy(
          states =
            mapOf(
              entity to
                example.snapshot.states.getValue(entity).let {
                  it.copy(
                    attributes =
                      JsonObject(
                        it.attributes +
                          mapOf(
                            "current_position" to JsonPrimitive(40),
                            "current_temperature" to JsonPrimitive(17.5),
                            "temperature" to JsonPrimitive(22),
                          ),
                      ),
                  )
                },
            ),
        )
      val bound = CardDesignTemplates.playback(example.card, bytes).bindings(live, SystemHaClock)
      val pushed = bound.strings.keys + bound.booleans.keys + bound.ints.keys + bound.floats.keys
      assertEquals(
        emptySet(),
        reads(bytes) - pushed,
        "${design.designId} reads values no host pushes",
      )
      when (design.domain) {
        "cover" ->
          if ("cover.s0.current_position" in reads(bytes))
            assertEquals(40f, bound.floats["cover.s0.current_position"], design.designId)
        "climate" -> {
          assertEquals(17.5f, bound.floats["climate.s0.current_temperature"], design.designId)
          assertEquals(22f, bound.floats["climate.s0.target_temperature"], design.designId)
        }
      }
      assertEquals(
        DevicePackLibrary.recipe(design).toJson(),
        CardDesignTemplates.embeddedRecipe(bytes)?.toJson(),
      )
    }
  }
}
