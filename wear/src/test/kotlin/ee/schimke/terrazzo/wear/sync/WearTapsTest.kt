package ee.schimke.terrazzo.wear.sync

import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.decodeHaAction
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A watch card's taps, as the watch sends them to the phone: see [WearTapDispatcher]. */
class WearTapsTest {
  private val card = """{"type":"tile","entity":"light.kitchen"}"""
  private val alarm = "alarm_control_panel.house"

  @Test
  fun aTapRequestCarriesTheCardAsSentAndTheActionAsHaActionJson() {
    val action =
      HaAction.CallService(
        "light",
        "turn_on",
        "light.kitchen",
        JsonObject(mapOf("brightness_pct" to JsonPrimitive(40))),
      )
    val request = Json.parseToJsonElement(wearTapRequest("id-1", card, action).decodeToString())
    val fields = request.jsonObject
    assertEquals(setOf("id", "card", "action"), fields.keys)
    assertEquals("id-1", fields["id"]?.jsonPrimitive?.content)
    // The card's text exactly as the phone sent it: the phone looks its card up by it.
    assertEquals(card, fields["card"]?.jsonPrimitive?.content)
    assertEquals(action, decodeHaAction(fields["action"]?.jsonPrimitive?.content))
  }

  @Test
  fun thePhonesAnswerSaysWhetherItRan() {
    assertNull(wearTapRefusal("""{"id":"1","ok":true}""".encodeToByteArray()))
    assertEquals(
      "Demo mode is read-only",
      wearTapRefusal(
        """{"id":"1","ok":false,"error":"Demo mode is read-only"}""".encodeToByteArray()
      ),
    )
    assertEquals("The phone didn't run it", wearTapRefusal("""{"ok":false}""".encodeToByteArray()))
    assertEquals("The phone's answer couldn't be read", wearTapRefusal("nope".encodeToByteArray()))
  }

  @Test
  fun tapsNeedThePhonesInputEditingAndNoDemo() {
    val on = WearSettings(inputEditingEnabled = true, baseUrl = "https://ha.example")
    assertTrue(wearTapsEnabled(on))
    assertFalse(wearTapsEnabled(on.copy(inputEditingEnabled = false)))
    assertFalse(wearTapsEnabled(on.copy(demoMode = true)))
    assertFalse(wearTapsEnabled(on.copy(baseUrl = "")))
  }

  private class Recorder {
    val sent = mutableListOf<Pair<String, HaAction>>()
    val outcomes = mutableListOf<WearTapOutcome>()
    val failures = mutableListOf<String>()
  }

  private fun TestScope.dispatcher(
    recorder: Recorder,
    enabled: Boolean = true,
    fail: String? = null,
  ) =
    WearTapDispatcher(
      card = card,
      scope = this,
      enabled = { enabled },
      send = { c, a ->
        recorder.sent += c to a
        fail?.let { error(it) }
      },
      onOutcome = { _, outcome -> recorder.outcomes += outcome },
      onFailure = { recorder.failures += it },
    )

  /** The phone not answering in time is an unconfirmed tap, not a silent "Sending…". */
  @Test
  fun anUnansweredTapIsReportedAsUnconfirmed() = runTest {
    val recorder = Recorder()
    val taps =
      WearTapDispatcher(
        card = card,
        scope = this,
        enabled = { true },
        send = { _, _ ->
          kotlinx.coroutines.withTimeout(10) { kotlinx.coroutines.awaitCancellation() }
        },
        onOutcome = { _, outcome -> recorder.outcomes += outcome },
        onFailure = { recorder.failures += it },
      )
    taps.dispatch(HaAction.Toggle("light.kitchen"))
    advanceUntilIdle()
    assertEquals(listOf(WearTapOutcome.Sent, WearTapOutcome.Failed), recorder.outcomes)
    assertEquals(listOf(WearTapDispatcher.NOT_CONFIRMED), recorder.failures)
  }

  @Test
  fun toggleAndServiceCallTapsGoToThePhone() = runTest {
    val recorder = Recorder()
    val taps = dispatcher(recorder)
    taps.dispatch(HaAction.Toggle("light.kitchen"))
    taps.dispatch(HaAction.CallService("scene", "turn_on", "scene.evening"))
    advanceUntilIdle()
    assertEquals(
      listOf(
        card to HaAction.Toggle("light.kitchen"),
        card to HaAction.CallService("scene", "turn_on", "scene.evening"),
      ),
      recorder.sent,
    )
    assertEquals(
      listOf(WearTapOutcome.Sent, WearTapOutcome.Sent, WearTapOutcome.Done, WearTapOutcome.Done),
      recorder.outcomes,
    )
  }

  @Test
  fun moreInfoNavigateAndUrlStayOnTheWatch() = runTest {
    val recorder = Recorder()
    val taps = dispatcher(recorder)
    taps.dispatch(HaAction.MoreInfo("light.kitchen"))
    taps.dispatch(HaAction.Navigate("/lovelace/1"))
    taps.dispatch(HaAction.Url("https://example.com"))
    taps.dispatch(HaAction.None)
    advanceUntilIdle()
    assertTrue(recorder.sent.isEmpty())
    assertTrue(recorder.outcomes.isEmpty())
  }

  @Test
  fun aTapWhileTapsAreOffIsNotSentAndSaysHowToTurnThemOn() = runTest {
    val recorder = Recorder()
    val taps = dispatcher(recorder, enabled = false)
    taps.dispatch(HaAction.Toggle("light.kitchen"))
    advanceUntilIdle()
    assertTrue(recorder.sent.isEmpty())
    assertEquals(listOf(WearTapOutcome.Failed), recorder.outcomes)
    assertEquals(listOf(WearTapDispatcher.TAPS_OFF), recorder.failures)
  }

  @Test
  fun aRefusedTapShowsThePhonesReason() = runTest {
    val recorder = Recorder()
    val taps = dispatcher(recorder, fail = "Card is no longer on the watch")
    taps.dispatch(HaAction.Toggle("light.kitchen"))
    advanceUntilIdle()
    assertEquals(listOf(WearTapOutcome.Sent, WearTapOutcome.Failed), recorder.outcomes)
    assertEquals(listOf("Card is no longer on the watch"), recorder.failures)
  }

  @Test
  fun anAlarmCodeAndItsButtonAreOneCall() = runTest {
    val recorder = Recorder()
    val taps = dispatcher(recorder)
    // The panel's order: type the code, then ARM.
    for (key in listOf("1", "2", "9", "backspace", "3", "4")) taps.dispatch(
      HaAction.AlarmKey(alarm, key)
    )
    taps.dispatch(HaAction.AlarmIntent(alarm, "arm_away", codeLength = 4))
    advanceUntilIdle()
    assertEquals<List<Pair<String, HaAction>>>(
      listOf(
        card to
          HaAction.CallService(
            "alarm_control_panel",
            "alarm_arm_away",
            alarm,
            JsonObject(mapOf("code" to JsonPrimitive("1234"))),
          )
      ),
      recorder.sent,
    )
    // Each key is felt, and the completed attempt is sent once.
    assertEquals(6, recorder.outcomes.count { it == WearTapOutcome.Key })
    assertEquals(1, recorder.outcomes.count { it == WearTapOutcome.Sent })
  }

  @Test
  fun anAlarmButtonFirstCallsOnceItsCodeIsComplete() = runTest {
    val recorder = Recorder()
    val taps = dispatcher(recorder)
    taps.dispatch(HaAction.AlarmIntent(alarm, "disarm", codeLength = 4))
    for (key in listOf("4", "3", "2")) taps.dispatch(HaAction.AlarmKey(alarm, key))
    // Not yet: three of four digits. (Run only what is due now, not the idle timeout.)
    testScheduler.runCurrent()
    assertTrue(recorder.sent.isEmpty())
    taps.dispatch(HaAction.AlarmKey(alarm, "1"))
    advanceUntilIdle()
    assertEquals<List<Pair<String, HaAction>>>(
      listOf(
        card to
          HaAction.CallService(
            "alarm_control_panel",
            "alarm_disarm",
            alarm,
            JsonObject(mapOf("code" to JsonPrimitive("4321"))),
          )
      ),
      recorder.sent,
    )
  }

  @Test
  fun anAlarmButtonWithNoCodeCallsAtOnce() = runTest {
    val recorder = Recorder()
    val taps = dispatcher(recorder)
    taps.dispatch(HaAction.AlarmIntent(alarm, "arm_home", codeLength = 0))
    advanceUntilIdle()
    assertEquals<List<Pair<String, HaAction>>>(
      listOf(
        card to
          HaAction.CallService(
            "alarm_control_panel",
            "alarm_arm_home",
            alarm,
            JsonObject(emptyMap()),
          )
      ),
      recorder.sent,
    )
  }
}
