package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaBambuPrintPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class BambuPrintStatusDataTest {
  private fun entity(id: String, state: String, vararg attrs: Pair<String, String>) =
    EntityState(id, state, JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) }))

  private fun snapshot(vararg states: EntityState) =
    HaSnapshot(states = states.associateBy { it.entityId })

  /** The demo dashboard's A1 mini, switched on and not printing. */
  private val idleA1Mini =
    listOf(
      entity(
        "sensor.a1mini_sn1_print_progress",
        "0",
        "friendly_name" to "A1MINI_sn1 Print progress",
      ),
      entity(
        "sensor.a1mini_sn1_print_status",
        "idle",
        "friendly_name" to "A1MINI_sn1 Print status",
      ),
      entity("sensor.a1mini_sn1_current_stage", "idle"),
      entity("sensor.a1mini_sn1_printer_name", "3DP-030-293"),
      entity("sensor.a1mini_sn1_current_layer", "0"),
      entity("sensor.a1mini_sn1_total_layer_count", "0"),
      entity("sensor.a1mini_sn1_remaining_time", "0.0"),
      entity("sensor.a1mini_sn1_nozzle_temperature", "23", "unit_of_measurement" to "°C"),
      entity("sensor.a1mini_sn1_bed_temperature", "22", "unit_of_measurement" to "°C"),
    )

  @Test
  fun anIdlePrinterShowsItsStateNotAnEmptyJob() {
    val data = buildPrintStatusData("a1mini_sn1", snapshot(*idleA1Mini.toTypedArray()))
    assertEquals(HaBambuPrintPhase.Idle, data.phase)
    assertEquals("Idle", data.stage)
    assertEquals("A1 mini", data.printerName)
    assertNull(data.layerLine)
    assertNull(data.remainingLine)
    assertEquals("Nozzle 23 °C", data.nozzleLine)
    assertEquals("Bed 22 °C", data.bedLine)
  }

  @Test
  fun aPrintingPrinterKeepsItsJobLines() {
    val data =
      buildPrintStatusData(
        "x1c",
        snapshot(
          entity("sensor.x1c_print_progress", "42", "friendly_name" to "X1C Print progress"),
          entity("sensor.x1c_print_status", "running"),
          entity("sensor.x1c_current_stage", "printing"),
          entity("sensor.x1c_current_layer", "120"),
          entity("sensor.x1c_total_layer_count", "300"),
          entity("sensor.x1c_remaining_time", "95.0"),
        ),
      )
    assertEquals(HaBambuPrintPhase.Printing, data.phase)
    assertEquals("Printing", data.stage)
    assertEquals("X1 Carbon", data.printerName)
    assertEquals("Layer 120 / 300", data.layerLine)
    assertEquals("1h 35m left", data.remainingLine)
  }

  @Test
  fun remainingTimeInHoursIsConvertedToMinutes() {
    val data =
      buildPrintStatusData(
        "x1c",
        snapshot(
          entity("sensor.x1c_print_progress", "42"),
          entity("sensor.x1c_print_status", "running"),
          entity("sensor.x1c_remaining_time", "1.5", "unit_of_measurement" to "h"),
        ),
      )
    assertEquals("1h 30m left", data.remainingLine)
  }

  @Test
  fun phaseReadsPrintStatusThenStageThenProgress() {
    fun phase(status: String?, stage: String? = null, online: String? = null) =
      bambuPrintPhase(status, stage, online, progress = 0f, totalLayers = 0)
    assertEquals(HaBambuPrintPhase.Finished, phase("finish"))
    assertEquals(HaBambuPrintPhase.Failed, phase("failed"))
    assertEquals(HaBambuPrintPhase.Offline, phase("offline"))
    assertEquals(HaBambuPrintPhase.Offline, phase("running", online = "off"))
    assertEquals(HaBambuPrintPhase.Printing, phase("prepare"))
    assertEquals(HaBambuPrintPhase.Printing, phase("pause"))
    assertEquals(HaBambuPrintPhase.Printing, phase("unknown", stage = "heatbed_preheating"))
    assertEquals(HaBambuPrintPhase.Idle, phase(null))
    assertEquals(
      HaBambuPrintPhase.Printing,
      bambuPrintPhase(null, null, null, progress = 12f, totalLayers = 200),
    )
  }

  @Test
  fun printerNamePrefersTheOwnersNameAndDropsTheEntityName() {
    fun name(vararg states: EntityState) = bambuPrinterName("p2s_sn2", snapshot(*states))
    val status =
      entity("sensor.p2s_sn2_print_status", "idle", "friendly_name" to "P2S_sn2 Print status")
    assertEquals("P2S Kodu", name(status, entity("sensor.p2s_sn2_printer_name", "P2S Kodu ")))
    assertEquals("P2S", name(status, entity("sensor.p2s_sn2_printer_name", "3DP-001-002")))
    assertEquals(
      "Garage printer",
      name(
        entity(
          "sensor.p2s_sn2_print_progress",
          "0",
          "friendly_name" to "Garage printer Print progress",
        )
      ),
    )
    assertEquals(
      "P1S",
      bambuPrinterName(
        "p1s_01p00a123456789",
        snapshot(
          entity(
            "sensor.p1s_01p00a123456789_print_status",
            "idle",
            "friendly_name" to "P1S_01P00A123456789 Print status",
          )
        ),
      ),
    )
  }

  @Test
  fun targetTemperaturesAreReadUnderEitherName() {
    // The integration's ids, as the demo P2S reports them while heating.
    val current =
      buildPrintStatusData(
        "p2s_sn2",
        snapshot(
          entity("sensor.p2s_sn2_print_status", "idle"),
          entity("sensor.p2s_sn2_nozzle_temperature", "180", "unit_of_measurement" to "°C"),
          entity("sensor.p2s_sn2_nozzle_target_temperature", "220"),
          entity("sensor.p2s_sn2_bed_temperature", "50", "unit_of_measurement" to "°C"),
          entity("sensor.p2s_sn2_bed_target_temperature", "65"),
        ),
      )
    assertEquals("Nozzle 180 °C → 220 °C", current.nozzleLine)
    assertEquals("Bed 50 °C → 65 °C", current.bedLine)
    // The older `target_<part>_temperature` ids still read.
    val older =
      buildPrintStatusData(
        "a1mini_sn1",
        snapshot(
          entity("sensor.a1mini_sn1_print_status", "idle"),
          entity("sensor.a1mini_sn1_nozzle_temperature", "180", "unit_of_measurement" to "°C"),
          entity("sensor.a1mini_sn1_target_nozzle_temperature", "220"),
          entity("sensor.a1mini_sn1_bed_temperature", "50", "unit_of_measurement" to "°C"),
          entity("sensor.a1mini_sn1_target_bed_temperature", "65"),
        ),
      )
    assertEquals("Nozzle 180 °C → 220 °C", older.nozzleLine)
    assertEquals("Bed 50 °C → 65 °C", older.bedLine)
  }
}
