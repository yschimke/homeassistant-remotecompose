package ee.schimke.terrazzo.widget

import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.encodeHaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
  fun togglesServiceCallsAndAlarmTapsRun() {
    assertTrue(WidgetActionRunner.runs(toggle))
    assertTrue(WidgetActionRunner.runs(HaAction.CallService("cover", "open_cover", "cover.garage")))
    // A script or scene names no entity of its own: it still runs, with nothing to show at once.
    assertTrue(WidgetActionRunner.runs(HaAction.CallService("script", "turn_on")))
    assertTrue(WidgetActionRunner.runs(HaAction.AlarmPin("alarm_control_panel.home", "1234")))
    assertTrue(
      WidgetActionRunner.runs(HaAction.AlarmIntent("alarm_control_panel.home", "arm_away"))
    )
    assertTrue(WidgetActionRunner.runs(HaAction.AlarmKey("alarm_control_panel.home", "1")))
    // These open the app (or a browser) through their own activity intent instead.
    assertFalse(WidgetActionRunner.runs(HaAction.MoreInfo("light.kitchen")))
    assertFalse(WidgetActionRunner.runs(HaAction.Url("https://example.com")))
  }

  /** The receiver pairs alarm taps as they arrive, before handing the call to its pool. */
  @Test
  fun alarmTapsPairInTheOrderTheyArrive() {
    val house = "alarm_control_panel.prepare_test"
    for (key in listOf("1", "2", "backspace", "3")) assertNull(
      WidgetActionRunner.prepare(41, HaAction.AlarmKey(house, key))
    )
    val call =
      assertIs<HaAction.CallService>(
        WidgetActionRunner.prepare(41, HaAction.AlarmIntent(house, "arm_away", codeLength = null))
      )
    assertEquals("alarm_arm_away", call.service)
    assertEquals("13", call.serviceData["code"]?.toString()?.trim('"'))
    // A toggle needs no pair; a more-info isn't the runner's.
    assertEquals(toggle, WidgetActionRunner.prepare(41, toggle))
    assertNull(WidgetActionRunner.prepare(41, HaAction.MoreInfo("light.kitchen")))
  }

  @Test
  fun aWidgetPinIsTrustedOnlyForTheEntityTheAppPutOnIt() {
    val placeholder = encodeHaAction(HaAction.AlarmPin("alarm_control_panel.home", ""))
    assertTrue(
      trustedWidgetAction(HaAction.AlarmPin("alarm_control_panel.home", "1234"), placeholder, null)
    )
    assertFalse(
      trustedWidgetAction(HaAction.AlarmPin("alarm_control_panel.shed", "1234"), placeholder, null)
    )
    // A PIN the app captured with digits is held to exactly those.
    assertFalse(
      trustedWidgetAction(
        HaAction.AlarmPin("alarm_control_panel.home", "9999"),
        encodeHaAction(HaAction.AlarmPin("alarm_control_panel.home", "1234")),
        null,
      )
    )
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
