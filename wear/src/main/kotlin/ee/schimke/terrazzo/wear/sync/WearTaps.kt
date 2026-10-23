package ee.schimke.terrazzo.wear.sync

import ee.schimke.ha.rc.AlarmKeypadCoordinator
import ee.schimke.ha.rc.HaActionDispatcher
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.encodeHaAction
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Whether a tap on a watch card may ask the phone to act: the phone allows input editing (which it
 * only reports for a signed-in, non-demo session) and isn't in demo mode. The same gate as the
 * message card's send.
 */
internal fun wearTapsEnabled(settings: WearSettings): Boolean =
  settings.inputEditingEnabled && !settings.demoMode && settings.baseUrl.isNotEmpty()

/** The largest tap request the phone reads: see `MobileWearSyncManager.handleTapRequest`. */
internal const val WEAR_TAP_MAX_BYTES = 32768

/**
 * The `/wear/tap/v1` request for a tap on [card] (its `rawJson`, exactly as the phone sent it) that
 * resolved to [action]: `{"id", "card", "action"}`, the action as its `HaAction` JSON.
 */
internal fun wearTapRequest(id: String, card: String, action: HaAction): ByteArray =
  buildJsonObject {
    put("id", JsonPrimitive(id))
    put("card", JsonPrimitive(card))
    put("action", JsonPrimitive(encodeHaAction(action)))
  }
  .toString()
  .encodeToByteArray()

/** The phone's reason a tap didn't run, from its `/wear/tap-result/v1` answer; null when it ran. */
internal fun wearTapRefusal(result: ByteArray): String? {
  val answer =
    runCatching { Json.parseToJsonElement(result.decodeToString()).jsonObject }.getOrNull()
      ?: return "The phone's answer couldn't be read"
  if (answer["ok"]?.jsonPrimitive?.contentOrNull == "true") return null
  return answer["error"]?.jsonPrimitive?.contentOrNull ?: "The phone didn't run it"
}

/** How a watch card tap went, for the card's feedback. */
internal enum class WearTapOutcome {
  /** Sent to the phone (a toggle, a service call, a completed alarm attempt). */
  Sent,
  /** A keypad key: held on the watch until its alarm attempt completes. */
  Key,
  /** The phone ran it. */
  Done,
  /** Not sent (taps are off) or the phone didn't run it: [WearTapDispatcher.onFailure] says why. */
  Failed,
}

/**
 * The [HaActionDispatcher] for one watch card ([card], its `rawJson`): a toggle or service call
 * goes to the phone ([send], `WearSyncRepository.sendTap`), which runs it only when its own copy of
 * the card makes it. An alarm panel's keys and ARM / DISARM buttons are paired here first, by the
 * dashboard's own [AlarmKeypadCoordinator], into one `alarm_control_panel.alarm_<service>` call
 * with the typed code; the phone checks that call against the card's own buttons.
 *
 * More-info, navigate and URL taps do nothing on the watch: it has no entity details, no dashboard
 * navigation and no browser to show them in.
 *
 * Each tap reports to [onOutcome] at once (the watch's haptic / visual feedback belongs to the
 * touch), and again when the phone answers; a failure also reaches [onFailure] with what to show.
 */
internal class WearTapDispatcher(
  private val card: String,
  private val scope: CoroutineScope,
  private val enabled: () -> Boolean,
  private val send: suspend (card: String, action: HaAction) -> Unit,
  private val onOutcome: (HaAction, WearTapOutcome) -> Unit = { _, _ -> },
  private val onFailure: (String) -> Unit = {},
  alarmIdleTimeoutMs: Long = AlarmKeypadCoordinator.DEFAULT_IDLE_TIMEOUT_MS,
) : HaActionDispatcher {

  private val toPhone = HaActionDispatcher { action ->
    when (action) {
      is HaAction.Toggle,
      is HaAction.CallService -> relay(action)
      else -> Unit
    }
  }

  private val alarms = AlarmKeypadCoordinator(toPhone, scope, alarmIdleTimeoutMs)

  override fun dispatch(action: HaAction) {
    when (action) {
      is HaAction.Toggle,
      is HaAction.CallService,
      is HaAction.AlarmIntent,
      is HaAction.AlarmPin,
      is HaAction.AlarmKey -> {
        if (!enabled()) {
          onOutcome(action, WearTapOutcome.Failed)
          onFailure(TAPS_OFF)
          return
        }
        if (action is HaAction.AlarmKey) onOutcome(action, WearTapOutcome.Key)
        alarms.dispatch(action)
      }
      // Only show something: nothing on the watch to show it in.
      is HaAction.MoreInfo,
      is HaAction.Navigate,
      is HaAction.Url,
      HaAction.None -> Unit
    }
  }

  private fun relay(action: HaAction) {
    onOutcome(action, WearTapOutcome.Sent)
    scope.launch {
      try {
        send(card, action)
        onOutcome(action, WearTapOutcome.Done)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        onOutcome(action, WearTapOutcome.Failed)
        onFailure(failure.message?.takeIf { it.isNotBlank() } ?: NOT_CONFIRMED)
      }
    }
  }

  companion object {
    const val TAPS_OFF = "Turn on watch controls on the phone"
    const val NOT_CONFIRMED = "Could not confirm. Check the phone before retrying."
  }
}
