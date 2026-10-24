package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaBambuPrintControlData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The print-control pad shows only the action the print's state allows — Pause while printing,
 * Resume while paused, Stop in either — and a control whose entity is unavailable is inert.
 */
class BambuPrintControlTest {
  private val card =
    CardConfig(
      type = "custom:ha-bambulab-print_control-card",
      raw =
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("custom:ha-bambulab-print_control-card"),
            "printer" to JsonPrimitive("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"),
          )
        ),
    )

  private fun snapshot(status: String, buttons: String = "unknown", online: String = "on") =
    HaSnapshot(
      states =
        listOf(
            EntityState("sensor.x1c_print_status", status),
            EntityState("sensor.x1c_print_progress", if (status == "idle") "0" else "40"),
            EntityState("binary_sensor.x1c_online", online),
            EntityState("button.x1c_pause_printing", buttons),
            EntityState("button.x1c_resume_printing", buttons),
            EntityState("button.x1c_stop_printing", buttons),
          )
          .associateBy { it.entityId }
    )

  private fun pad(snapshot: HaSnapshot): HaBambuPrintControlData =
    assertNotNull(buildPrintControlData(card, snapshot))

  private fun HaBambuPrintControlData.shown() =
    listOfNotNull(pause, resume, stop).map { it.label to it.enabled }

  @Test
  fun printingOffersPauseAndStop() {
    val data = pad(snapshot("running"))
    assertEquals(listOf("Pause" to true, "Stop" to true), data.shown())
    assertNull(data.statusLine)
    assertEquals(
      "button.x1c_pause_printing",
      (data.pause!!.tapAction as HaAction.CallService).entityId,
    )
  }

  @Test
  fun pausedOffersResumeAndStop() {
    assertEquals(listOf("Resume" to true, "Stop" to true), pad(snapshot("pause")).shown())
  }

  @Test
  fun anIdlePrinterOffersNothingAndSaysSo() {
    val data = pad(snapshot("idle", buttons = "unavailable"))
    assertTrue(data.shown().isEmpty())
    assertEquals("Nothing printing", data.statusLine)
    assertEquals("Print finished", pad(snapshot("finish")).statusLine)
    assertEquals("Printer offline", pad(snapshot("running", online = "off")).statusLine)
  }

  @Test
  fun unavailableControlsAreShownButInert() {
    val snap = snapshot("running", buttons = "unavailable")
    val data = pad(snap)
    assertEquals(listOf("Pause" to false, "Stop" to false), data.shown())
    // The design path presses nothing either.
    val converter = BambuLabPrintControlCardConverter()
    val press =
      HaAction.CallService(
        domain = "button",
        service = "press",
        entityId = "button.${BambuDesign.PREFIX}_pause_printing",
        serviceData = JsonObject(emptyMap()),
      )
    assertNull(converter.designAction(card, snap, 0, press))
    assertNotNull(converter.designAction(card, snapshot("running"), 0, press))
  }

  @Test
  fun aHiddenControlCannotBePressedThroughTheTemplate() {
    val converter = BambuLabPrintControlCardConverter()
    val resume =
      HaAction.CallService(
        domain = "button",
        service = "press",
        entityId = "button.${BambuDesign.PREFIX}_resume_printing",
        serviceData = JsonObject(emptyMap()),
      )
    assertNull(converter.designAction(card, snapshot("running"), 0, resume))
  }

  @Test
  fun aPrinterThatReportsNoPrintStateKeepsEveryAvailableControl() {
    val snap =
      HaSnapshot(
        states =
          listOf(
              EntityState("button.x1c_pause_printing", "unknown"),
              EntityState("button.x1c_resume_printing", "unavailable"),
              EntityState("button.x1c_stop_printing", "unknown"),
            )
            .associateBy { it.entityId }
      )
    val data = pad(snap)
    assertEquals(listOf("Pause" to true, "Resume" to false, "Stop" to true), data.shown())
    val converter = BambuLabPrintControlCardConverter()
    val template =
      assertNotNull(converter.designTemplate(assertNotNull(converter.designProperties(card, snap))))
    val replayed = assertNotNull(buildPrintControlData(template.card, template.snapshot))
    assertEquals(data.shown(), replayed.shown())
  }

  @Test
  fun theTemplateReproducesTheShownControls() {
    val converter = BambuLabPrintControlCardConverter()
    for (snap in
      listOf(
        snapshot("running"),
        snapshot("pause"),
        snapshot("running", buttons = "unavailable"),
        snapshot("idle"),
      )) {
      val properties = assertNotNull(converter.designProperties(card, snap))
      val template = assertNotNull(converter.designTemplate(properties))
      val real = pad(snap)
      val replayed = assertNotNull(buildPrintControlData(template.card, template.snapshot))
      assertEquals(real.shown(), replayed.shown(), "for $properties")
      assertEquals(real.statusLine != null, replayed.statusLine != null)
    }
  }

  @Test
  fun theSignatureFollowsThePhaseAndAvailability() {
    val converter = BambuLabPrintControlCardConverter()
    val signatures =
      listOf(
          snapshot("running"),
          snapshot("pause"),
          snapshot("running", buttons = "unavailable"),
        )
        .map { converter.dataSignature(card, it) }
    assertEquals(3, signatures.toSet().size)
  }
}
