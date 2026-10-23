package ee.schimke.terrazzo.widget

import ee.schimke.ha.rc.components.HaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** An alarm panel widget's separate taps, paired into one `alarm_control_panel` call. */
class WidgetAlarmPairingTest {

  private val house = "alarm_control_panel.house"
  private var time = 0L
  private val pairing = WidgetAlarmPairing(now = { time })

  private fun call(service: String, code: String? = null) =
    HaAction.CallService(
      domain = "alarm_control_panel",
      service = "alarm_$service",
      entityId = house,
      serviceData = JsonObject(code?.let { mapOf("code" to JsonPrimitive(it)) }.orEmpty()),
    )

  @Test
  fun anArmThatNeedsNoCodeCallsAtOnce() {
    assertEquals(
      call("arm_away"),
      pairing.accept(1, HaAction.AlarmIntent(house, "arm_away", codeLength = 0)),
    )
  }

  @Test
  fun aCodeThenTheButtonCallsWithTheCode() {
    assertNull(pairing.accept(1, HaAction.AlarmPin(house, "1234")))
    assertEquals(
      call("disarm", "1234"),
      pairing.accept(1, HaAction.AlarmIntent(house, "disarm", codeLength = 4)),
    )
    // The pair is spent: the next tap starts again.
    assertNull(pairing.accept(1, HaAction.AlarmIntent(house, "disarm", codeLength = 4)))
  }

  @Test
  fun theButtonThenAKnownLengthCodeCallsWhenItIsComplete() {
    assertNull(pairing.accept(1, HaAction.AlarmIntent(house, "arm_home", codeLength = 4)))
    assertEquals(call("arm_home", "4321"), pairing.accept(1, HaAction.AlarmPin(house, "4321")))
  }

  @Test
  fun keysOfAnUnknownLengthCodeCallOnTheButtonAfterThem() {
    for (key in listOf("1", "2", "9", "backspace", "3")) assertNull(
      pairing.accept(1, HaAction.AlarmKey(house, key))
    )
    assertEquals(
      call("arm_away", "123"),
      pairing.accept(1, HaAction.AlarmIntent(house, "arm_away", codeLength = null)),
    )
  }

  @Test
  fun aCodeLeftLongerThanTheWindowIsForgotten() {
    assertNull(pairing.accept(1, HaAction.AlarmPin(house, "1234")))
    time += WidgetAlarmPairing.PAIRING_WINDOW_MS + 1
    assertNull(pairing.accept(1, HaAction.AlarmIntent(house, "disarm", codeLength = 4)))
  }

  @Test
  fun aCodeOnOneWidgetIsNotAnothersCode() {
    assertNull(pairing.accept(1, HaAction.AlarmPin(house, "1234")))
    assertNull(pairing.accept(2, HaAction.AlarmIntent(house, "disarm", codeLength = 4)))
  }
}
