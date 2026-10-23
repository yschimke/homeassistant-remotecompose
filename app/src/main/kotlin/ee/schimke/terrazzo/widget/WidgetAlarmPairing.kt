package ee.schimke.terrazzo.widget

import ee.schimke.ha.rc.AlarmKeypadCoordinator
import ee.schimke.ha.rc.components.HaAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pairs a launcher alarm panel's taps into one `alarm_control_panel.alarm_<service>` call. Each tap
 * reaches the app as its own broadcast: an ARM or DISARM button's [HaAction.AlarmIntent], the
 * keypad's completed [HaAction.AlarmPin] (a known code length, accumulated by the widget's
 * document) or its single [HaAction.AlarmKey]s (an unknown length). The app's own dashboard pairs
 * them in [AlarmKeypadCoordinator]; a widget has no screen session to hold that state in, so this
 * keeps it per widget and entity, and only for [windowMs]: a code typed and left is forgotten
 * rather than sent with a later, unrelated tap.
 *
 * - An action that needs no code (`codeLength` 0) calls at once.
 * - A code entered first calls on the button's tap; a button tapped first calls once a code of its
 *   known length is complete.
 * - A code of unknown length calls on the button's tap after it: the panel's own order, type the
 *   code and press ARM.
 *
 * Home Assistant checks the code; a wrong one fails the call, which the runner logs.
 */
internal class WidgetAlarmPairing(
  private val now: () -> Long = System::currentTimeMillis,
  private val windowMs: Long = PAIRING_WINDOW_MS,
) {
  private class Pending(var intent: HaAction.AlarmIntent?, val code: StringBuilder, var at: Long)

  private val pending = HashMap<String, Pending>()

  /** The service call [action] completes for [widgetId], or null while it waits for its pair. */
  @Synchronized
  fun accept(widgetId: Int, action: HaAction): HaAction.CallService? {
    val entityId =
      when (action) {
        is HaAction.AlarmIntent -> action.entityId
        is HaAction.AlarmPin -> action.entityId
        is HaAction.AlarmKey -> action.entityId
        else -> return null
      }
    val key = "$widgetId/$entityId"
    val time = now()
    val state =
      pending[key]?.takeIf { time - it.at <= windowMs }
        ?: Pending(null, StringBuilder(), time).also { pending[key] = it }
    state.at = time
    when (action) {
      is HaAction.AlarmIntent -> {
        state.intent = action
        if (action.codeLength == 0 || state.code.isNotEmpty()) return flush(key)
      }
      is HaAction.AlarmPin -> {
        state.code.clear()
        state.code.append(action.pin.filter(Char::isDigit))
        if (state.intent != null) return flush(key)
      }
      is HaAction.AlarmKey -> {
        when (action.key) {
          AlarmKeypadCoordinator.BACKSPACE ->
            if (state.code.isNotEmpty()) state.code.deleteCharAt(state.code.length - 1)
          AlarmKeypadCoordinator.CLEAR -> state.code.clear()
          else ->
            if (action.key.length == 1 && action.key[0].isDigit()) state.code.append(action.key)
        }
        val length = state.intent?.codeLength
        if (length != null && length > 0 && state.code.length >= length) return flush(key)
      }
      else -> Unit
    }
    return null
  }

  private fun flush(key: String): HaAction.CallService? {
    val state = pending.remove(key) ?: return null
    val intent = state.intent ?: return null
    val code = state.code.toString()
    return HaAction.CallService(
      domain = "alarm_control_panel",
      service = "alarm_${intent.service}",
      entityId = intent.entityId,
      serviceData =
        if (code.isEmpty() || intent.codeLength == 0) JsonObject(emptyMap())
        else JsonObject(mapOf("code" to JsonPrimitive(code))),
    )
  }

  companion object {
    /** How long a button tap or an entered code waits for its pair. */
    const val PAIRING_WINDOW_MS = 30_000L
  }
}
