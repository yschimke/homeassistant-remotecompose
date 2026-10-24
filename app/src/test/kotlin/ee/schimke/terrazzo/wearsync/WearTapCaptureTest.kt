package ee.schimke.terrazzo.wearsync

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.HaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone's own capture of a published card is what a watch tap is checked against
 * ([captureWearCardActions]): the card's real taps are in it, and a forged one isn't.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearTapCaptureTest {
  private val context = RuntimeEnvironment.getApplication()
  private val registry = defaultRegistry().withEnhancedShutter()

  private fun state(id: String, state: String, vararg attrs: Pair<String, String>) =
    EntityState(id, state, JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) }))

  @Test
  fun anEntitiesCardsRowToggleIsTheCardsAndRunsFromTheWatch() {
    val raw = """{"type":"entities","entities":["light.kitchen","sensor.temperature"]}"""
    val card = requireNotNull(raw.toCardConfigOrNull())
    val snapshot =
      HaSnapshot(
        states =
          listOf(
              state("light.kitchen", "on", "friendly_name" to "Kitchen"),
              state("sensor.temperature", "21.5", "unit_of_measurement" to "°C"),
            )
            .associateBy { it.entityId }
      )
    val actions = captureWearCardActions(context, card, snapshot, registry)
    assertTrue(HaAction.Toggle("light.kitchen") in actions, "captured: $actions")

    val published = mapOf(raw to card)
    val run = { action: HaAction ->
      wearTapCall(raw, action, published, readOnly = false) {
        captureWearCardActions(context, it, snapshot, registry)
      }
    }
    assertEquals(HaAction.Toggle("light.kitchen"), run(HaAction.Toggle("light.kitchen")))
    // An entity the card doesn't show, and a service it doesn't call.
    assertFailsWith<IllegalArgumentException> { run(HaAction.Toggle("lock.front_door")) }
    assertFailsWith<IllegalArgumentException> {
      run(HaAction.CallService("lock", "unlock", "lock.front_door"))
    }
  }

  @Test
  fun anAlarmPanelsButtonsAndKeypadAreTheCards() {
    val raw = """{"type":"alarm-panel","entity":"alarm_control_panel.house"}"""
    val card = requireNotNull(raw.toCardConfigOrNull())
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "alarm_control_panel.house" to
              state("alarm_control_panel.house", "disarmed", "friendly_name" to "House")
          )
      )
    val actions = captureWearCardActions(context, card, snapshot, registry)
    val buttons = actions.filterIsInstance<HaAction.AlarmIntent>()
    assertTrue(
      buttons.any { it.entityId == "alarm_control_panel.house" && it.service == "arm_away" },
      "captured: $actions",
    )
    // The watch's paired call for that button and a typed code is rebuilt from it.
    val call =
      wearTapCall(
        raw,
        HaAction.CallService(
          "alarm_control_panel",
          "alarm_arm_away",
          "alarm_control_panel.house",
          JsonObject(mapOf("code" to JsonPrimitive("1234"))),
        ),
        mapOf(raw to card),
        readOnly = false,
      ) {
        actions
      }
    assertEquals("alarm_arm_away", (call as HaAction.CallService).service)
    assertEquals("alarm_control_panel.house", call.entityId)
    // A disarmed panel shows no DISARM button: its call is refused.
    assertFailsWith<IllegalArgumentException> {
      wearTapCall(
        raw,
        HaAction.CallService("alarm_control_panel", "alarm_disarm", "alarm_control_panel.house"),
        mapOf(raw to card),
        readOnly = false,
      ) {
        actions
      }
    }
  }
}
