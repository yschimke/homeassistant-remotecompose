package ee.schimke.terrazzo.wearsync

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.rc.components.HaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The phone's rule for a tap on a watch card: see [wearTapCall]. */
class WearTapActionTest {
  private val lightRaw = """{"type":"button","entity":"light.kitchen"}"""
  private val alarmRaw = """{"type":"alarm-panel","entity":"alarm_control_panel.house"}"""
  private val light = card(lightRaw)
  private val alarm = card(alarmRaw)
  private val published = mapOf(lightRaw to light, alarmRaw to alarm)

  private val toggle = HaAction.Toggle("light.kitchen")
  private val dim =
    HaAction.CallService(
      "light",
      "turn_on",
      "light.kitchen",
      JsonObject(mapOf("brightness_pct" to JsonPrimitive(40))),
    )
  private val armAway = HaAction.AlarmIntent("alarm_control_panel.house", "arm_away", 4)

  /** What the phone's capture of each card makes: the fake for [captureWearCardActions]. */
  private val produced: (CardConfig) -> Set<HaAction> = { card ->
    when (card) {
      light -> setOf(toggle, dim, HaAction.MoreInfo("light.kitchen"))
      alarm ->
        setOf(
          armAway,
          HaAction.AlarmIntent("alarm_control_panel.house", "arm_home", 4),
          HaAction.AlarmKey("alarm_control_panel.house", "1"),
        )
      else -> emptySet()
    }
  }

  private fun call(
    raw: String?,
    action: HaAction?,
    readOnly: Boolean = false,
    cards: Map<String, CardConfig> = published,
  ) = wearTapCall(raw, action, cards, readOnly, produced)

  @Test
  fun anActionTheCardMakesRuns() {
    assertEquals(toggle, call(lightRaw, toggle))
    assertEquals(dim, call(lightRaw, dim))
  }

  @Test
  fun anyOtherActionIsRefused() {
    // Another entity, other service data, another service: none of them is the card's.
    assertFailsWith<IllegalArgumentException> { call(lightRaw, HaAction.Toggle("lock.front")) }
    assertFailsWith<IllegalArgumentException> {
      call(
        lightRaw,
        dim.copy(serviceData = JsonObject(mapOf("brightness_pct" to JsonPrimitive(100)))),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      call(lightRaw, HaAction.CallService("script", "turn_on", "script.open_garage"))
    }
    // The light card's action, sent as if from the alarm card.
    assertFailsWith<IllegalArgumentException> { call(alarmRaw, toggle) }
    // Undecodable.
    assertFailsWith<IllegalArgumentException> { call(lightRaw, null) }
  }

  @Test
  fun onlyCallsRunEvenWhenTheCardMakesTheTap() {
    // The card does make a more-info tap; the phone has nothing to run for it.
    assertFailsWith<IllegalArgumentException> { call(lightRaw, HaAction.MoreInfo("light.kitchen")) }
    assertFailsWith<IllegalArgumentException> { call(alarmRaw, armAway) }
  }

  @Test
  fun aCardThePhoneDidNotPublishIsRefused() {
    assertFailsWith<IllegalArgumentException> {
      call("""{"type":"button","entity":"lock.front"}""", HaAction.Toggle("lock.front"))
    }
    // The same card config, re-encoded: only the exact published text names a card.
    assertFailsWith<IllegalArgumentException> {
      call("""{"entity":"light.kitchen","type":"button"}""", toggle)
    }
    assertFailsWith<IllegalArgumentException> { call(null, toggle) }
    // A card no longer published (a new session clears them).
    assertFailsWith<IllegalArgumentException> { call(lightRaw, toggle, cards = emptyMap()) }
  }

  @Test
  fun readOnlyAndDemoAreRefused() {
    // The manager passes read-only for both: input editing off, or a demo session.
    assertFailsWith<IllegalArgumentException> { call(lightRaw, toggle, readOnly = true) }
  }

  @Test
  fun aPairedAlarmCallIsRebuiltFromTheCardsOwnButton() {
    val armed =
      call(
        alarmRaw,
        HaAction.CallService(
          "alarm_control_panel",
          "alarm_arm_away",
          "alarm_control_panel.house",
          JsonObject(mapOf("code" to JsonPrimitive("1234"))),
        ),
      )
    assertEquals(
      HaAction.CallService(
        "alarm_control_panel",
        "alarm_arm_away",
        "alarm_control_panel.house",
        JsonObject(mapOf("code" to JsonPrimitive("1234"))),
      ),
      armed,
    )
    // No code typed: the button's call without one, for Home Assistant to check.
    assertEquals(
      HaAction.CallService(
        "alarm_control_panel",
        "alarm_arm_home",
        "alarm_control_panel.house",
        JsonObject(emptyMap()),
      ),
      call(
        alarmRaw,
        HaAction.CallService("alarm_control_panel", "alarm_arm_home", "alarm_control_panel.house"),
      ),
    )
  }

  @Test
  fun anAlarmCallTheCardDoesNotOfferIsRefused() {
    fun alarmCall(
      service: String,
      entity: String = "alarm_control_panel.house",
      data: String = "{}",
    ) =
      HaAction.CallService(
        "alarm_control_panel",
        service,
        entity,
        Json.parseToJsonElement(data).jsonObject,
      )
    // No DISARM button on this card (the panel is disarmed), another panel, extra data.
    assertFailsWith<IllegalArgumentException> { call(alarmRaw, alarmCall("alarm_disarm")) }
    assertFailsWith<IllegalArgumentException> {
      call(alarmRaw, alarmCall("alarm_arm_away", entity = "alarm_control_panel.garage"))
    }
    assertFailsWith<IllegalArgumentException> {
      call(alarmRaw, alarmCall("alarm_arm_away", data = """{"code":"1234","mode":"x"}"""))
    }
    assertFailsWith<IllegalArgumentException> {
      call(alarmRaw, alarmCall("alarm_arm_away", data = """{"code":"12ab"}"""))
    }
    // A code for a panel card that shows no keypad.
    val noKeypad = mapOf(alarmRaw to alarm)
    assertFailsWith<IllegalArgumentException> {
      wearTapCall(
        alarmRaw,
        alarmCall("alarm_arm_away", data = """{"code":"1234"}"""),
        noKeypad,
        false,
      ) {
        setOf(armAway)
      }
    }
    // An alarm call from a card that isn't an alarm panel.
    assertFailsWith<IllegalArgumentException> { call(lightRaw, alarmCall("alarm_arm_away")) }
  }

  @Test
  fun refusalsSayWhyAndFailuresSayToCheckThePhone() {
    val refusal = runCatching { call(lightRaw, toggle, readOnly = true) }.exceptionOrNull()
    assertEquals("This host is read-only", wearTapError(refusal))
    assertEquals(
      "Home Assistant didn't confirm it. Check the phone connection.",
      wearTapError(IllegalStateException("socket closed")),
    )
  }

  private fun card(raw: String): CardConfig = requireNotNull(raw.toCardConfigOrNull())
}
