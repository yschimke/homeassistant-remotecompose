@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.wearsync

import android.content.Context
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.ProvideCardSizeMode
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.ProvideExpressionNames
import ee.schimke.ha.rc.components.ProvideWidgetActionRegistry
import ee.schimke.ha.rc.components.WidgetActionRegistry
import ee.schimke.ha.rc.components.WidgetOpenApp
import ee.schimke.ha.rc.decodeHaAction
import ee.schimke.terrazzo.widget.WidgetAlarmPairing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The Home Assistant call a tap on a watch card makes, or a refusal ([IllegalArgumentException]).
 *
 * The watch names the card it played ([rawCard], its `rawJson` exactly as the phone sent it) and
 * the action its tap resolved to ([tapped]). The phone runs nothing the watch composed: [rawCard]
 * must be a card the phone itself published to the watch ([publishedCards]), and [tapped] one of
 * the actions the phone's own capture of that card produces ([cardActions], see
 * [captureWearCardActions]) — the rule a launcher widget's taps follow
 * ([ee.schimke.terrazzo.widget.trustedWidgetAction]).
 *
 * Runs only a toggle or a service call. An alarm panel's taps are paired on the watch (its
 * `AlarmKeypadCoordinator`) into one `alarm_control_panel.alarm_<service>` call carrying the typed
 * code; the phone accepts it only when the card itself shows that ARM / DISARM button for that
 * entity (and a keypad, when a code came with it), and rebuilds the call from its own button with
 * [WidgetAlarmPairing]. More-info, navigate and URL taps only show something, which the watch does
 * itself (or not at all): they are refused here.
 */
internal fun wearTapCall(
  rawCard: String?,
  tapped: HaAction?,
  publishedCards: Map<String, CardConfig>,
  readOnly: Boolean,
  cardActions: (CardConfig) -> Set<HaAction>,
): HaAction {
  require(!readOnly) { "This host is read-only" }
  val card = requireNotNull(publishedCards[rawCard]) { "Card is no longer on the watch" }
  requireNotNull(tapped) { "Unreadable action" }
  require(tapped is HaAction.Toggle || tapped is HaAction.CallService) {
    "Only toggles and service calls run from the watch"
  }
  val produced = cardActions(card)
  if (tapped in produced) return tapped
  val alarm =
    (tapped as? HaAction.CallService)?.let { alarmCall(it, produced) }
      ?: throw IllegalArgumentException("The card doesn't make this call")
  return alarm
}

/**
 * [call] rebuilt from the card's own ARM / DISARM button, when it is a paired alarm-panel call the
 * card makes; null otherwise.
 */
private fun alarmCall(call: HaAction.CallService, produced: Set<HaAction>): HaAction.CallService? {
  if (call.domain != ALARM_DOMAIN || !call.service.startsWith(ALARM_SERVICE_PREFIX)) return null
  val entityId = call.entityId ?: return null
  val service = call.service.removePrefix(ALARM_SERVICE_PREFIX)
  val button =
    produced.filterIsInstance<HaAction.AlarmIntent>().firstOrNull {
      it.entityId == entityId && it.service == service
    } ?: return null
  // A code and nothing else: no other service data reaches Home Assistant from the watch.
  if (call.serviceData.keys.any { it != CODE_KEY }) return null
  val code =
    call.serviceData[CODE_KEY]
      ?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString } }
      ?.contentOrNull
      .orEmpty()
  if (!code.all(Char::isDigit) || code.length > MAX_CODE_LENGTH) return null
  val hasKeypad = produced.any {
    (it is HaAction.AlarmKey && it.entityId == entityId) ||
      (it is HaAction.AlarmPin && it.entityId == entityId)
  }
  if (code.isNotEmpty() && !hasKeypad) return null
  // The phone's own button, with the typed code: one pairing, one call.
  val pairing = WidgetAlarmPairing()
  if (code.isNotEmpty()) pairing.accept(WATCH_PAIRING_ID, HaAction.AlarmPin(entityId, code))
  return pairing.accept(WATCH_PAIRING_ID, button)
    // No code typed for a button that wants one: Home Assistant checks it, as on the dashboard.
    ?: HaAction.CallService(
      domain = ALARM_DOMAIN,
      service = ALARM_SERVICE_PREFIX + button.service,
      entityId = entityId,
      serviceData = JsonObject(emptyMap()),
    )
}

/**
 * Every action [card] can fire, from the phone's own capture of it on [snapshot]: each clickable
 * part registers its action ([WidgetActionRegistry]), in Wrap mode (the full card) and in Fixed
 * mode (every size tier the watch's container may pick), so whichever of them the watch drew is
 * covered. The surface's own open-app tap ([WidgetOpenApp]) is no card action and is left out.
 */
internal fun captureWearCardActions(
  context: Context,
  card: CardConfig,
  snapshot: HaSnapshot,
  registry: CardRegistry,
): Set<HaAction> = buildSet {
  for (mode in listOf(CardSizeMode.Wrap, CardSizeMode.Fixed)) {
    val actions = WidgetActionRegistry(emptyMap())
    runCatching {
      runBlocking {
        captureSingleRemoteDocument(
          context = context,
          creationDisplayInfo = TAP_CAPTURE_DISPLAY,
          profile = androidXExperimentalWrap,
        ) {
          ProvideCardRegistry(registry) {
            ProvideCardSizeMode(mode) {
              ProvideWidgetActionRegistry(actions) {
                ProvideExpressionNames {
                  RenderChild(card, snapshot, RemoteModifier.fillMaxWidth())
                }
              }
            }
          }
        }
      }
    }
      .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    actions.entries.values.mapNotNullTo(this) { payload ->
      decodeHaAction(payload)?.takeUnless { it == WidgetOpenApp }
    }
  }
}

/** A Wear Large container's canvas, at mdpi: what the watch draws its cards at. */
private val TAP_CAPTURE_DISPLAY = RemoteCreationDisplayInfo(200, 112, 160)

private const val ALARM_DOMAIN = "alarm_control_panel"
private const val ALARM_SERVICE_PREFIX = "alarm_"
private const val CODE_KEY = "code"
private const val MAX_CODE_LENGTH = 32
private const val WATCH_PAIRING_ID = 0

/**
 * What the watch shows for a tap the phone didn't run: a refusal says why (read-only, demo, a card
 * no longer on the watch); a call that failed says to check the connection.
 */
internal fun wearTapError(failure: Throwable?): String =
  when (failure) {
    is IllegalArgumentException -> failure.message ?: "The phone refused this tap"
    else -> "Home Assistant didn't confirm it. Check the phone connection."
  }
