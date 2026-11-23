package ee.schimke.ha.designs

import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.cards.DevicePacks
import ee.schimke.ha.design.CardEntityActions
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DeviceMatch
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CommonCaptureHost
import ee.schimke.ha.rc.SystemHaClock
import ee.schimke.ha.rc.components.HA_ENTITY_ACTION_NAME
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaEntityAction
import ee.schimke.ha.rc.components.encodeHaEntityAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
    val garage =
      DevicePackLibrary.published.first { it.designId == "garage-door-controls-skeuomorphic" }
    val thermostat =
      DevicePackLibrary.published.first { it.designId == "thermostat-controls-skeuomorphic" }
    assertEquals(DeviceMatch(deviceClass = "garage"), DevicePackLibrary.device(garage))
    assertNull(DevicePackLibrary.device(thermostat))
    assertEquals("pack:garage-door-controls-skeuomorphic", DevicePackLibrary.designId(garage))
    assertEquals("thermostat", DevicePackLibrary.cardType(thermostat))
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
                            "current_temperature" to JsonPrimitive(63.5),
                            "temperature" to JsonPrimitive(68),
                            "min_temp" to JsonPrimitive(45),
                            "max_temp" to JsonPrimitive(95),
                            "hvac_action" to JsonPrimitive("heating"),
                          ),
                      ),
                  )
                },
            ),
        )
      // A thermostat in a °F home: the design reads its unit, not the °C it was drawn with.
      val bound =
        CardDesignTemplates.playback(example.card, bytes)
          .bindings(live.copy(temperatureUnit = "°F"), SystemHaClock)
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
          assertEquals(63.5f, bound.floats["climate.s0.current_temperature"], design.designId)
          assertEquals(68f, bound.floats["climate.s0.target_temperature"], design.designId)
          assertEquals(45f, bound.floats["climate.s0.min_temp"], design.designId)
          assertEquals(95f, bound.floats["climate.s0.max_temp"], design.designId)
          assertEquals("°F", bound.strings["climate.s0.unit"], design.designId)
          assertEquals(
            "63.5°F",
            bound.strings["climate.s0.attributes.current_temperature_label"],
            design.designId,
          )
          assertEquals(
            "↑ 68°F",
            bound.strings["climate.s0.attributes.temperature_label"],
            design.designId,
          )
          assertEquals(
            "Heating",
            bound.strings["climate.s0.attributes.hvac_action_label"],
            design.designId,
          )
        }
      }
      assertEquals(
        DevicePackLibrary.recipe(design).toJson(),
        CardDesignTemplates.embeddedRecipe(bytes)?.toJson(),
      )
    }
  }

  @Test
  fun aGarageDoorDesignIsFoundInEveryStateTheDoorIsIn() {
    val design =
      DevicePackLibrary.published.first { it.designId == "garage-door-controls-skeuomorphic" }
    val keys = DevicePackLibrary.keys(design)
    assertTrue(keys.all { "state_icon=*" in it.shape }, keys.first().shape)
    val example = DevicePackLibrary.example(design)
    val entity = example.snapshot.states.keys.single()
    for (state in listOf("closed", "open", "opening", "closing")) {
      val snapshot =
        example.snapshot.copy(
          states = mapOf(entity to example.snapshot.states.getValue(entity).copy(state = state)),
        )
      val cardKeys = CardDesignLibrary.keysOf(example.card, snapshot)
      assertTrue(cardKeys.isNotEmpty(), state)
      for (card in cardKeys) {
        assertTrue(
          keys.any { it.accepts(card) },
          "no pack key for $state in ${card.theme}: ${card.shape}",
        )
      }
    }
  }

  /** The `ha-cards/button` nodes of [design] as the library captures it, by node id. */
  private fun buttons(design: DevicePacks.PackDesign): Map<String, JsonObject> =
    Json.parseToJsonElement(DevicePacks.forSlots(design))
      .jsonObject
      .getValue("nodes")
      .jsonObject
      .mapValues { it.value.jsonObject }
      .filterValues { it["componentId"]?.jsonPrimitive?.content == "ha-cards/button" }

  private fun JsonObject.property(name: String): String? =
    this["properties"]?.jsonObject?.get(name)?.jsonObject?.get("value")?.jsonPrimitive?.content

  /** The contract file of [design]'s pack: its actions, by name. */
  private fun contractActions(design: DevicePacks.PackDesign): Set<String> {
    val file = java.io.File("ui-builder/packs/contracts/ha-device-${design.domain}.json")
    return Json.parseToJsonElement(file.readText())
      .jsonObject
      .getValue("actions")
      .jsonObject
      .values
      .mapNotNull { it.jsonObject["kind"]?.jsonPrimitive?.content }
      .toSet()
  }

  @Test
  fun aPublishedDesignsButtonsDoWhatTheySay() {
    // A button either toggles its entity (a garage's wall push button) or sends a named entity
    // action its contract declares (`app:ha.cover.close_cover`), which the host runs as that
    // service on the card's own entity: never the card's tap for a labelled control, since Close
    // sending a cover's toggle would open a closed door.
    for (design in DevicePackLibrary.published) {
      val declared = contractActions(design)
      for ((id, node) in buttons(design)) {
        val action = node.property("action")
        if (action == null) {
          assertEquals(
            "true",
            node.property("toggleable"),
            "${design.designId}/$id is a button that neither toggles nor names an action",
          )
          continue
        }
        assertTrue(
          action in CardEntityActions.actionsFor(design.domain),
          "${design.designId}/$id sends `$action`, no ${design.domain} entity action",
        )
        assertTrue(
          "app:ha.${design.domain}.$action" in declared,
          "${design.designId}/$id sends `$action`, which its contract doesn't declare",
        )
      }
    }
  }

  @Test
  fun theGarageDoorsOpenStopAndCloseEachRunTheirOwnService() {
    val design =
      DevicePackLibrary.published.first { it.designId == "garage-door-controls-material3" }
    val labelled = buttons(design).values.associate { it.property("name") to it.property("action") }
    assertEquals<Map<String?, String?>>(
      mapOf("Open" to "open_cover", "Stop" to "stop_cover", "Close" to "close_cover"),
      labelled,
    )
    val example = DevicePackLibrary.example(design)
    val bytes =
      DevicePackLibrary.capture(host, DevicePackLibrary.keys(design).first(), design).bytes
    val text = String(bytes, Charsets.ISO_8859_1)
    assertTrue(HA_ENTITY_ACTION_NAME in text, "the buttons send entity actions")
    // Labelled by what they do, not by the door's name as the card's own button would be.
    for (label in listOf("Open", "Stop", "Close")) assertTrue(label in text, "labelled $label")
    assertEquals(CardEntityActions.NAME, HA_ENTITY_ACTION_NAME)
    // The card is someone's own garage door: each button runs its service on that door.
    val card =
      CardConfig(
        "tile",
        buildJsonObject {
          put("type", "tile")
          put("entity", "cover.my_garage")
          // The card's own tap stays a toggle; the buttons don't send it.
          put("tap_action", buildJsonObject { put("action", "toggle") })
        },
      )
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "cover.my_garage" to
              example.snapshot.states.values
                .single()
                .copy(
                  entityId = "cover.my_garage",
                  attributes = JsonObject(mapOf("supported_features" to JsonPrimitive(15))),
                ),
          ),
      )
    val playback = CardDesignTemplates.playback(card, bytes)
    for (service in listOf("open_cover", "stop_cover", "close_cover")) {
      val payload = encodeHaEntityAction(HaEntityAction("cover.s0", service))
      assertTrue(payload in text, "the template sends $payload")
      assertEquals(
        HaAction.CallService("cover", service, "cover.my_garage"),
        playback.resolveEntityAction(payload, snapshot),
      )
    }
    // Only the card's own slot, and only its domain's actions.
    assertEquals(
      HaAction.None,
      playback.resolveEntityAction(encodeHaEntityAction(HaEntityAction("cover.s1", "open_cover"))),
    )
    assertEquals(
      HaAction.None,
      playback.resolveEntityAction(encodeHaEntityAction(HaEntityAction("lock.s0", "unlock"))),
    )
    assertEquals(
      HaAction.None,
      playback.resolveEntityAction(encodeHaEntityAction(HaEntityAction("cover.s0", "unlock"))),
    )
    assertEquals(HaAction.None, playback.resolveEntityAction("{not json"))
  }

  @Test
  fun aThermostatsStepButtonsStepItsSetPoint() {
    val design =
      DevicePackLibrary.published.first { it.designId == "thermostat-controls-material3" }
    val bytes =
      DevicePackLibrary.capture(host, DevicePackLibrary.keys(design).first(), design).bytes
    val text = String(bytes, Charsets.ISO_8859_1)
    val card =
      CardConfig(
        "thermostat",
        buildJsonObject {
          put("type", "thermostat")
          put("entity", "climate.den")
        },
      )
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "climate.den" to
              EntityState(
                "climate.den",
                "heat",
                JsonObject(
                  mapOf(
                    "temperature" to JsonPrimitive(70),
                    "min_temp" to JsonPrimitive(45),
                    "max_temp" to JsonPrimitive(95),
                  ),
                ),
              ),
          ),
        temperatureUnit = "°F",
      )
    val playback = CardDesignTemplates.playback(card, bytes)
    for ((action, next) in listOf("temperature_up" to 71, "temperature_down" to 69)) {
      val payload = encodeHaEntityAction(HaEntityAction("climate.s0", action))
      assertTrue(payload in text, "the template sends $payload")
      assertEquals(
        HaAction.CallService(
          "climate",
          "set_temperature",
          "climate.den",
          JsonObject(mapOf("temperature" to JsonPrimitive(next))),
        ),
        playback.resolveEntityAction(payload, snapshot),
      )
    }
  }
}
