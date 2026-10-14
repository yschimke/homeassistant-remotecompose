package ee.schimke.terrazzo.feedback

import ee.schimke.ha.rc.components.HaAction

/**
 * A moment worth feeling or hearing. Only actions that change something in the home get one: a card
 * tap that merely opens a URL or more-info already has its ripple, and a buzz on every tap would
 * drown out the ones that matter.
 */
enum class FeedbackCue {
  /** A switch, light or cover was turned on (or opened). */
  ToggleOn,
  /** A switch, light or cover was turned off (or closed). */
  ToggleOff,
  /** A service call whose direction isn't on/off — a scene, a script, a step. */
  Press,
  /** One key of a keypad. */
  Key,
  /** An alarm panel was armed or disarmed. */
  Confirm,
  /** The action was dropped (read-only mode) or the call failed. */
  Reject,
}

/**
 * The cue for [action], or null for none. [stateOf] reads an entity's current state, so a bare
 * toggle can tell which way it is about to flip; an unknown state falls back to
 * [FeedbackCue.Press].
 */
fun feedbackCueFor(action: HaAction, stateOf: (String) -> String? = { null }): FeedbackCue? =
  when (action) {
    is HaAction.Toggle ->
      when (stateOf(action.entityId)) {
        in OnStates -> FeedbackCue.ToggleOff
        in OffStates -> FeedbackCue.ToggleOn
        else -> FeedbackCue.Press
      }
    is HaAction.CallService ->
      when (action.service) {
        in OnServices -> FeedbackCue.ToggleOn
        in OffServices -> FeedbackCue.ToggleOff
        "toggle" ->
          action.entityId?.let { feedbackCueFor(HaAction.Toggle(it), stateOf) } ?: FeedbackCue.Press
        else -> FeedbackCue.Press
      }
    is HaAction.AlarmKey -> FeedbackCue.Key
    is HaAction.AlarmPin,
    is HaAction.AlarmIntent -> FeedbackCue.Confirm
    is HaAction.MoreInfo,
    is HaAction.Navigate,
    is HaAction.Url,
    HaAction.None -> null
  }

private val OnStates = setOf("on", "open", "opening", "unlocked", "playing", "home")
private val OffStates = setOf("off", "closed", "closing", "locked", "paused", "idle", "not_home")
private val OnServices = setOf("turn_on", "open_cover", "unlock", "media_play")
private val OffServices = setOf("turn_off", "close_cover", "lock", "media_pause", "media_stop")
