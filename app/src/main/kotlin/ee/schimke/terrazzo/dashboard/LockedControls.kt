package ee.schimke.terrazzo.dashboard

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.HighRiskActions
import ee.schimke.ha.rc.components.HaAction

/**
 * A write a card sent while the app's controls were locked, which the dispatcher dropped. [replay]
 * runs it as if they had been unlocked; it is null for a high-risk action (a lock, an alarm, a
 * garage door), which is never run on the strength of an "Unlock" tap: it is tapped again.
 */
class LockedAction(val replay: (() -> Unit)?)

/** [action] as a [LockedAction] that replays with [replay] unless it is high risk on [snapshot]. */
internal fun lockedAction(
  action: HaAction,
  snapshot: HaSnapshot?,
  replay: () -> Unit,
): LockedAction {
  // Without a snapshot an entity's class (a cover that might be a garage door) isn't known.
  val highRisk = snapshot == null || HighRiskActions.isHighRisk(action, snapshot)
  return LockedAction(replay.takeUnless { highRisk })
}

internal const val CONTROLS_LOCKED = "Controls are locked"
internal const val CONTROLS_LOCKED_TAP_AGAIN = "Controls are locked. Unlock, then tap again."
internal const val UNLOCK = "Unlock"

/**
 * Says why a tap did nothing, where it was made, and offers to fix it: "Controls are locked" with
 * an Unlock action. Unlocking calls [unlock] and then runs the tap (when it may be replayed), so
 * the user doesn't have to find the top bar's lock and tap the card a second time.
 *
 * A newer locked tap replaces the prompt still showing rather than queueing behind it.
 */
internal suspend fun SnackbarHostState.offerUnlock(locked: LockedAction, unlock: () -> Unit) {
  currentSnackbarData?.takeIf { it.visuals.message.startsWith(CONTROLS_LOCKED) }?.dismiss()
  val result =
    showSnackbar(
      message = if (locked.replay != null) CONTROLS_LOCKED else CONTROLS_LOCKED_TAP_AGAIN,
      actionLabel = UNLOCK,
      withDismissAction = false,
      duration = SnackbarDuration.Short,
    )
  if (result == SnackbarResult.ActionPerformed) {
    unlock()
    locked.replay?.invoke()
  }
}
