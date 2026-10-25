package ee.schimke.terrazzo.widget

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A high-risk widget tap's confirmation ([WidgetConfirmActivity]): what it runs and asks. */
class WidgetConfirmTest {
  private val unlock = HaAction.CallService("lock", "unlock", "lock.front_door")

  @Test
  fun itRunsTogglesAndServiceCallsOnly() {
    assertTrue(WidgetConfirmActivity.confirms(unlock))
    assertTrue(WidgetConfirmActivity.confirms(HaAction.Toggle("cover.garage")))
    // Alarm panels pair their code in the receiver; the panel's code is its own check.
    assertFalse(
      WidgetConfirmActivity.confirms(HaAction.AlarmIntent("alarm_control_panel.a", "disarm"))
    )
    assertFalse(WidgetConfirmActivity.confirms(HaAction.MoreInfo("lock.front_door")))
  }

  @Test
  fun itAsksByTheEntitysNameAndTheActionsVerb() {
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "lock.front_door" to
              EntityState(
                "lock.front_door",
                "locked",
                JsonObject(mapOf("friendly_name" to JsonPrimitive("Front door"))),
              )
          )
      )
    assertEquals("Unlock Front door?", widgetConfirmPrompt(unlock, snapshot))
    // No snapshot on disk yet: the entity id is still a name.
    assertEquals("Unlock lock.front_door?", widgetConfirmPrompt(unlock, null))
    assertEquals(
      "Open cover.garage?",
      widgetConfirmPrompt(HaAction.CallService("cover", "open_cover", "cover.garage"), null),
    )
  }
}
