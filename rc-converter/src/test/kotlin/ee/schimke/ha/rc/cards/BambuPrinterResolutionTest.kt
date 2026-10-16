package ee.schimke.ha.rc.cards

import ee.schimke.ha.design.DeviceInfo
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Which printer a Bambu Lab card shows, with the demo "3D Printing" dashboard's two printers: an A1
 * mini (`a1mini_sn1`) and a P2S (`p2s_sn2`). A card names its printer by device id; it must show
 * that printer, the only one there is, or say it can't tell — never the first one it finds.
 */
class BambuPrinterResolutionTest {
  private fun entity(id: String, state: String) = EntityState(id, state, JsonObject(emptyMap()))

  private val a1mini =
    listOf(
      entity("sensor.a1mini_sn1_print_progress", "0"),
      entity("sensor.a1mini_sn1_print_status", "idle"),
      entity("button.a1mini_sn1_pause_printing", "unavailable"),
      entity("button.a1mini_sn1_resume_printing", "unavailable"),
      entity("button.a1mini_sn1_stop_printing", "unavailable"),
      entity("sensor.a1mini_sn1_ams_1_tray_1", "Generic PLA"),
      entity("sensor.a1mini_sn1_externalspool_external_spool", "Generic PLA"),
    )

  private val p2s =
    listOf(
      entity("sensor.p2s_sn2_print_progress", "0"),
      entity("sensor.p2s_sn2_print_status", "idle"),
      entity("sensor.p2s_sn2_ams_1_tray_1", "Bambu TPU for AMS"),
      entity("sensor.p2s_sn2_ams_1_tray_2", "Bambu PLA Basic"),
      entity("sensor.p2s_sn2_externalspool_external_spool", "Generic TPU"),
    )

  /** The registry: each entity's device, as `HaClient.fetchDevices` reports it. */
  private val registry: Map<String, DeviceInfo> =
    (a1mini + p2s).associate { e ->
      val objectId = e.entityId.substringAfter('.')
      val printer = if (objectId.startsWith("p2s_sn2_")) "dev-p2s" else "dev-a1"
      val device =
        when {
          "_ams_1_" in objectId -> "$printer-ams"
          "_externalspool_" in objectId -> "$printer-spool"
          else -> printer
        }
      e.entityId to DeviceInfo(integration = "bambu_lab", deviceId = device)
    }

  private fun snapshot(states: List<EntityState>, devices: Map<String, DeviceInfo> = emptyMap()) =
    HaSnapshot(states = states.associateBy { it.entityId }, devices = devices)

  private val both = snapshot(a1mini + p2s, registry)
  private val bothWithoutRegistry = snapshot(a1mini + p2s)

  private fun card(type: String, vararg fields: Pair<String, String>) =
    CardConfig(
      type = type,
      raw =
        JsonObject(
          mapOf("type" to JsonPrimitive(type)) +
            fields.associate { (k, v) -> k to JsonPrimitive(v) }
        ),
    )

  private fun status(printer: String) =
    card("custom:ha-bambulab-print_status-card", "printer" to printer)

  private fun control(printer: String) =
    card("custom:ha-bambulab-print_control-card", "printer" to printer)

  /** The demo config's redacted device id, which no registry knows. */
  private val redacted = "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"

  @Test
  fun everyPrinterInTheSnapshotIsACandidate() {
    assertEquals(listOf("a1mini_sn1", "p2s_sn2"), bambuPrinters(both))
  }

  @Test
  fun theConfiguredDeviceIdPicksItsPrinterThroughTheRegistry() {
    assertEquals("p2s_sn2", resolveBambuPrinter(status("dev-p2s"), both).prefix)
    assertEquals("a1mini_sn1", resolveBambuPrinter(status("dev-a1"), both).prefix)
    // Whichever printer happens to sort first.
    assertEquals("p2s_sn2", resolveBambuPrinter(control("dev-p2s"), both).prefix)
  }

  @Test
  fun anAmsDeviceIdPicksItsPrinterAndItsAms() {
    val printer =
      resolveBambuPrinter(card("custom:ha-bambulab-ams-card", "ams" to "dev-p2s-ams"), both)
    assertEquals("p2s_sn2", printer.prefix)
    assertEquals(1, printer.amsIndex)
  }

  @Test
  fun aSpoolDeviceIdShowsThatSpoolNotAnAmsTray() {
    val spoolCard = card("custom:ha-bambulab-spool-card", "spool" to "dev-p2s-spool")
    val slot = BambuLabSpoolCardConverter().chosenSlot(spoolCard, both)
    assertEquals("sensor.p2s_sn2_externalspool_external_spool", slot?.entityId)
    assertEquals("External spool", slot?.slotLabel)
  }

  @Test
  fun aSpoolEntityIdShowsThatSpool() {
    val spoolCard = card("custom:ha-bambulab-spool-card", "spool" to "sensor.p2s_sn2_ams_1_tray_2")
    val slot = BambuLabSpoolCardConverter().chosenSlot(spoolCard, bothWithoutRegistry)
    assertEquals("sensor.p2s_sn2_ams_1_tray_2", slot?.entityId)
  }

  @Test
  fun theCardsOwnEntityPicksItsPrinter() {
    val withEntity =
      card(
        "custom:ha-bambulab-print_status-card",
        "printer" to redacted,
        "entity" to "sensor.p2s_sn2_print_progress",
      )
    assertEquals("p2s_sn2", resolveBambuPrinter(withEntity, bothWithoutRegistry).prefix)
  }

  @Test
  fun theOnlyPrinterIsUsedWhenTheDeviceCannotBeResolved() {
    val one = snapshot(p2s)
    assertEquals("p2s_sn2", resolveBambuPrinter(status(redacted), one).prefix)
    assertEquals("p2s_sn2", resolveBambuPrinter(status("dev-a1"), one).prefix)
  }

  @Test
  fun severalPrintersAndNoWayToTellIsAmbiguousNotAGuess() {
    for (snap in listOf(bothWithoutRegistry, both)) {
      val printer = resolveBambuPrinter(status(redacted), snap)
      assertNull(printer.prefix)
      assertTrue(printer.ambiguous)
    }
    // A registry that names a device of no printer doesn't pick one either.
    assertNull(resolveBambuPrinter(status("dev-unrelated"), both).prefix)
  }

  @Test
  fun anAmbiguousControlPadOffersNoButtons() {
    val pad = control(redacted)
    assertNull(buildPrintControlData(pad, bothWithoutRegistry))
    val converter = BambuLabPrintControlCardConverter()
    assertNull(converter.designProperties(pad, bothWithoutRegistry))
    val templatePress =
      HaAction.CallService(
        domain = "button",
        service = "press",
        entityId = "button.${BambuDesign.PREFIX}_pause_printing",
        serviceData = JsonObject(emptyMap()),
      )
    assertNull(converter.designAction(pad, bothWithoutRegistry, 0, templatePress))
  }

  @Test
  fun theP2sPadNeverCarriesTheA1MinisButtons() {
    val data = buildPrintControlData(control("dev-p2s"), both)!!
    // The demo P2S has no button entities; the A1 mini's must not stand in for them.
    assertNull(data.pause)
    assertNull(data.resume)
    assertNull(data.stop)
    assertEquals("Nothing printing", data.statusLine)
  }

  @Test
  fun aSkipObjectCardResolvesTheSameWay() {
    val skip = card("custom:ha-bambulab-skipobject-card", "printer" to redacted)
    assertNull(buildPrintControlData(skip, bothWithoutRegistry))
    assertEquals(
      "a1mini_sn1",
      resolveBambuPrinter(card(skip.type, "printer" to "dev-a1"), both).prefix,
    )
  }

  @Test
  fun anotherIntegrationsPrintStatusIsNotAPrinter() {
    val office = EntityState("sensor.office_print_status", "idle", JsonObject(emptyMap()))
    val snap =
      HaSnapshot(
        states = (p2s + office).associateBy { it.entityId },
        devices = mapOf(office.entityId to DeviceInfo(integration = "ipp")),
      )
    assertEquals(listOf("p2s_sn2"), bambuPrinters(snap))
    assertEquals("p2s_sn2", resolveBambuPrinter(status(redacted), snap).prefix)
  }

  @Test
  fun theDataSignatureFollowsTheResolvedPrinter() {
    val converter = BambuLabPrintStatusCardConverter()
    val ambiguous = converter.dataSignature(status(redacted), bothWithoutRegistry)
    val p2sSignature = converter.dataSignature(status("dev-p2s"), both)
    val a1Signature = converter.dataSignature(status("dev-a1"), both)
    assertNotEquals(ambiguous, p2sSignature)
    assertNotEquals(p2sSignature, a1Signature)
    assertTrue(p2sSignature.startsWith("printer=p2s_sn2"))
    assertFalse(ambiguous.startsWith("printer="))
  }

  private fun CardConfig.string(key: String) = (raw[key] as? JsonPrimitive)?.content

  @Test
  fun resolvingDevicesPinsTheConfiguredPrinterAsAnExplicitPrefix() {
    val pinned = resolveBambuDevices(control("dev-p2s"), both)
    assertEquals("p2s_sn2", pinned.string("entity_prefix"))
    assertEquals("dev-p2s", pinned.string("printer"))
    // Without the registry — a Wear widget's snapshot — the pinned card is still the P2S's.
    assertEquals("p2s_sn2", resolveBambuPrinter(pinned, bothWithoutRegistry).prefix)
    assertTrue(resolveBambuPrinter(control("dev-p2s"), bothWithoutRegistry).ambiguous)
    assertEquals(
      "a1mini_sn1",
      resolveBambuPrinter(resolveBambuDevices(status("dev-a1"), both), bothWithoutRegistry).prefix,
    )
  }

  @Test
  fun resolvingDevicesKeepsWhichAmsAndWhichSpool() {
    val ams = resolveBambuDevices(card("custom:ha-bambulab-ams-card", "ams" to "dev-p2s-ams"), both)
    assertEquals("p2s_sn2", ams.string("entity_prefix"))
    assertEquals("sensor.p2s_sn2_ams_1_tray_1", ams.string("ams"))
    val amsPrinter = resolveBambuPrinter(ams, bothWithoutRegistry)
    assertEquals("p2s_sn2", amsPrinter.prefix)
    assertEquals(1, amsPrinter.amsIndex)

    val spool =
      resolveBambuDevices(card("custom:ha-bambulab-spool-card", "spool" to "dev-p2s-spool"), both)
    assertEquals("sensor.p2s_sn2_externalspool_external_spool", spool.string("spool"))
    val slot = BambuLabSpoolCardConverter().chosenSlot(spool, bothWithoutRegistry)
    assertEquals("sensor.p2s_sn2_externalspool_external_spool", slot?.entityId)
  }

  @Test
  fun resolvingDevicesReachesIntoStacks() {
    val stack =
      CardConfig(
        type = "vertical-stack",
        raw =
          JsonObject(
            mapOf(
              "type" to JsonPrimitive("vertical-stack"),
              "cards" to kotlinx.serialization.json.JsonArray(listOf(control("dev-a1").raw)),
            )
          ),
      )
    val inner =
      (resolveBambuDevices(stack, both).raw["cards"] as kotlinx.serialization.json.JsonArray)
    assertEquals(
      "a1mini_sn1",
      (inner.single() as JsonObject)["entity_prefix"]?.let { (it as JsonPrimitive).content },
    )
  }

  @Test
  fun resolvingDevicesLeavesWhatTheRegistryCannotTellUnchanged() {
    // Unresolvable: the card stays ambiguous rather than gaining a guessed prefix.
    assertEquals(status(redacted), resolveBambuDevices(status(redacted), both))
    assertEquals(status("dev-unrelated"), resolveBambuDevices(status("dev-unrelated"), both))
    // No registry to resolve with.
    assertEquals(status("dev-p2s"), resolveBambuDevices(status("dev-p2s"), bothWithoutRegistry))
    // Already explicit, or not a Bambu card.
    val explicit = card(status("x").type, "printer" to "dev-a1", "entity_prefix" to "p2s_sn2")
    assertEquals(explicit, resolveBambuDevices(explicit, both))
    val tile = card("tile", "entity" to "sensor.p2s_sn2_print_progress")
    assertEquals(tile, resolveBambuDevices(tile, both))
  }
}
