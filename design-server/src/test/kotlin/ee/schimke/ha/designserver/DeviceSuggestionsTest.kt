package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.design.BuiltDesign
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignManifest
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.design.DeviceMatch
import ee.schimke.ha.model.EntityState
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The device designs a dashboard editor suggests, and resolving a card set to one by its id. */
class DeviceSuggestionsTest {
  private val template = javaClass.getResource("/tile-light.rc")!!.readBytes()

  private fun key(theme: String = "baked:Material3:light", variant: String = "default") =
    DesignKey(
      cardType = "tile",
      variant = variant,
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=light",
      theme = theme,
    )

  private val printer = DeviceMatch(integration = " Bambu_Lab ", model = "X1*")

  /** The library's tile, plus a printer design of the same card in a variant of its own. */
  private fun library(): DesignLibrary {
    val dir = Files.createTempDirectory("designs").toFile()
    val keys = listOf(key(), key(variant = "printer"), key("baked:Material3:dark", "printer"))
    val output =
      DesignPublisher.publish(keys.map { BuiltDesign(it, 1, template) }, serial = 7, nowEpochMs = 1)
    for ((path, bytes) in output.files) File(dir, path)
      .also { it.parentFile.mkdirs() }
      .writeBytes(bytes)
    val designs =
      output.manifest.designs.map {
        if (it.key.variant == "printer")
          it.copy(
            designId = "user:printer",
            title = "Printer",
            author = "github:someone",
            device = printer,
          )
        else it
      }
    File(dir, "manifest.json")
      .writeText(
        Json.encodeToString(DesignManifest.serializer(), output.manifest.copy(designs = designs)),
      )
    return DesignLibrary.load(dir)
  }

  @Test
  fun aPacksContractIsTheInputsAndActionsItsTemplateBinds() {
    val (inputs, actions) =
      DeviceSuggestions.contract(
        listOf(
          "USER:sensor_operation_state",
          "binary_sensor_door",
          "light.s0.state",
          "host.dark",
          "meta.title",
          "HA:time",
          "card.title",
          "action_start",
          "action_start_visible",
          "action.eco_50.visible",
          "action_auto",
          "",
        ),
      )
    assertEquals(listOf("binary_sensor_door", "sensor_operation_state"), inputs)
    assertEquals(listOf("auto", "eco_50", "start"), actions)
  }

  @Test
  fun aTemplatesNamedColorsAreNoInputsAndAnUnreadableOneHasNoContract() {
    val template =
      DecodedTemplate(
        bindings =
          listOf(
            Binding("sensor_operation_state", "text"),
            Binding("accent", "color"),
            Binding("action_start", "int"),
          ),
        operations = 1,
        sizeByOperation = emptyMap(),
        violations = emptyList(),
        error = null,
      )
    val names = DeviceSuggestions.namedValues(template)!!
    assertEquals(listOf("sensor_operation_state", "action_start"), names)
    assertEquals(
      listOf("sensor_operation_state") to listOf("start"),
      DeviceSuggestions.contract(names),
    )
    assertNull(DeviceSuggestions.namedValues(DecodedTemplate.Missing))
  }

  @Test
  fun suggestionsCarryTheContractOnlyWhenTheTemplateDeclaresOne() {
    val library = library()
    val withContract =
      DeviceSuggestions.index(library.manifest.serial, library.manifest.designs) {
        listOf("sensor_remaining", "action_start", "light.s0.state")
      }
    val printer = withContract.designs.single()
    assertEquals(listOf("sensor_remaining"), printer.inputs)
    assertEquals(listOf("start"), printer.actions)
    // A template that binds only its slot has no contract to list, and none is encoded.
    val plain =
      DeviceSuggestions.index(library.manifest.serial, library.manifest.designs) {
        listOf("light.s0.state")
      }
    assertNull(plain.designs.single().inputs)
    assertEquals(false, DeviceSuggestions.encode(plain).contains("inputs"))
    // The real library template, decoded as the route does: still one printer suggestion.
    val decoded =
      DeviceSuggestions.index(library.manifest.serial, library.manifest.designs) { entry ->
        DeviceSuggestions.namedValues(library.decode(entry))
      }
    assertEquals(listOf("user:printer"), decoded.designs.map { it.designId })
  }

  @Test
  fun onlyDesignsMadeForADeviceAreSuggestedOncePerDesignAndCardType() {
    val library = library()
    val index = DeviceSuggestions.index(library.manifest.serial, library.manifest.designs)
    assertEquals(7, index.serial)
    assertEquals(
      listOf(
        DeviceSuggestions.Suggestion(
          designId = "user:printer",
          cardType = "tile",
          device = DeviceMatch(integration = "bambu_lab", model = "x1*"),
          title = "Printer",
          domain = "light",
        ),
      ),
      index.designs,
    )
  }

  @Test
  fun aLibraryDesignForAClassIsPublishedWithItsIdAndSuggestedForThatClass() {
    // What the library generator publishes for a device pack (DevicePackLibrary).
    val pack =
      BuiltDesign(
        key().copy(shape = "device_class=garage;domain=cover", variant = "pack-garage"),
        1,
        template,
        designId = "pack:garage-door-controls-skeuomorphic",
        title = "Garage door · Wall console",
        device = DeviceMatch(deviceClass = "garage"),
      )
    val output = DesignPublisher.publish(listOf(BuiltDesign(key(), 1, template), pack), 3, 1)
    val entry = output.manifest.designs.single { it.key.variant == "pack-garage" }
    assertEquals("pack:garage-door-controls-skeuomorphic", entry.designId)
    assertEquals("Garage door · Wall console", entry.title)
    assertEquals(
      "library:tile",
      output.manifest.designs.single { it.key.variant == "default" }.designId,
    )
    assertEquals(
      listOf(
        DeviceSuggestions.Suggestion(
          designId = "pack:garage-door-controls-skeuomorphic",
          cardType = "tile",
          device = DeviceMatch(deviceClass = "garage"),
          title = "Garage door · Wall console",
          domain = "cover",
        ),
      ),
      DeviceSuggestions.index(3, output.manifest.designs).designs,
    )
  }

  @Test
  fun revokedAndUnidentifiedDeviceDesignsAreNotSuggested() {
    val entries = library().manifest.designs
    val revoked = entries.map { if (it.device != null) it.copy(revoked = true) else it }
    assertEquals(emptyList(), DeviceSuggestions.index(1, revoked).designs)
    val empty = entries.map { if (it.device != null) it.copy(device = DeviceMatch()) else it }
    assertEquals(emptyList(), DeviceSuggestions.index(1, empty).designs)
  }

  private fun request(designId: String?, dark: Boolean = false) =
    ResolveRequest(
      card =
        buildJsonObject {
          put("type", "tile")
          put("entity", "light.printer_chamber")
        },
      states = mapOf("light.printer_chamber" to EntityState("light.printer_chamber", "on")),
      dark = dark,
      designId = designId,
    )

  @Test
  fun aCardSetToADesignResolvesToThatDesignInItsMode() {
    val library = library()
    assertEquals("default", CardResolve.resolve(library, request(null))!!.key.variant)
    val light = CardResolve.resolveChosen(library, request("user:printer"))!!
    assertEquals("printer", light.key.variant)
    assertEquals("baked:Material3:light", light.key.theme)
    assertEquals(
      "baked:Material3:dark",
      CardResolve.resolveChosen(library, request("user:printer", dark = true))!!.key.theme,
    )
    assertEquals(
      "default",
      CardResolve.resolveChosen(library, request("library:tile"))!!.key.variant,
    )
  }

  @Test
  fun aChoiceTheLibraryCantDrawLeavesTheCardToItsUsualDesign() {
    val library = library()
    assertNull(CardResolve.resolveChosen(library, request(null)))
    assertNull(CardResolve.resolveChosen(library, request("user:gone")))
  }

  @Test
  fun theRequestCarriesTheChoice() {
    val body =
      Json.parseToJsonElement(
        """{"card":{"type":"tile","entity":"light.a"},"designId":"user:printer"}""",
      )
    assertEquals("user:printer", ResolveRequest.parse(body).designId)
    val blank =
      Json.parseToJsonElement("""{"card":{"type":"tile","entity":"light.a"},"designId":" "}""")
    assertNull(ResolveRequest.parse(blank).designId)
  }
}
