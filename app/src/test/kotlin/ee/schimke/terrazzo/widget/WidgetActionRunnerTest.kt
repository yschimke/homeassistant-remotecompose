package ee.schimke.terrazzo.widget

import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.encodeHaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetActionRunnerTest {

  private val toggle = HaAction.Toggle("light.kitchen")

  @Test
  fun aWriteRunsOnlyAsTheAppsOwnAction() {
    assertTrue(trustedWidgetAction(toggle, encodeHaAction(toggle), resolvedAction = null))
    // A design widget's resolved action is the card's own, and wins over the document's payload.
    assertTrue(
      trustedWidgetAction(
        toggle,
        encodeHaAction(HaAction.Toggle("light.s0")),
        encodeHaAction(toggle),
      )
    )
    // Metadata the launcher filled in naming another entity, or with no payload of the app's own.
    assertFalse(
      trustedWidgetAction(HaAction.Toggle("lock.front_door"), encodeHaAction(toggle), null)
    )
    assertFalse(trustedWidgetAction(toggle, fallbackPayload = null, resolvedAction = null))
  }

  @Test
  fun onlyTogglesAndEntityServiceCallsRun() {
    assertTrue(WidgetActionRunner.runs(toggle))
    assertTrue(WidgetActionRunner.runs(HaAction.CallService("cover", "open_cover", "cover.garage")))
    assertFalse(WidgetActionRunner.runs(HaAction.CallService("script", "turn_on")))
    assertFalse(WidgetActionRunner.runs(HaAction.MoreInfo("light.kitchen")))
    assertFalse(WidgetActionRunner.runs(HaAction.AlarmPin("alarm_control_panel.home", "1234")))
  }

  @Test
  fun onOffActionsShowAtOnce() {
    assertEquals("on", optimisticState(toggle, "off"))
    assertEquals("off", optimisticState(toggle, "on"))
    assertEquals(
      "off",
      optimisticState(HaAction.CallService("light", "turn_off", "light.kitchen"), "on"),
    )
    // A cover's motion, or a toggle of a state that isn't on/off, waits for Home Assistant.
    assertNull(
      optimisticState(HaAction.CallService("cover", "open_cover", "cover.garage"), "closed")
    )
    assertNull(optimisticState(HaAction.Toggle("cover.garage"), "closed"))
  }
}
