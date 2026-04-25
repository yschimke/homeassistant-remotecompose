package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerInputChange
import kotlinx.coroutines.withTimeout

/**
 * Long-press detector that fires **before** a [androidx.compose.remote.tooling.preview.RemotePreview]
 * child consumes touch events.
 *
 * The RC player (alpha08) installs its own `Modifier.pointerInput`
 * that on the **Main** pass calls `awaitPointerEvent()` and then
 * `consume()` on every change so the player can dispatch in-document
 * click regions. A standard outer `Modifier.combinedClickable`
 * uses `awaitFirstDown(requireUnconsumed = true)` and therefore
 * never sees the down event — long-press from the parent silently
 * never fires.
 *
 * This modifier listens on the **Initial** pass instead, where it
 * gets each event before the player. It tracks press duration with
 * `withTimeout`; on long-press it fires [onLongPress] + a
 * `LongPress` haptic and consumes the rest of the gesture so the
 * player's release-time click logic doesn't also fire (best-effort —
 * the player doesn't filter on `requireUnconsumed`, so an in-document
 * click region under the press point may still fire on release; the
 * net UX is "install sheet opens AND the toggle the user happened to
 * be holding flips" which is recoverable, vs the previous "nothing
 * happens, ever").
 *
 * Tap (release before timeout) is unaffected — we never consume on
 * Initial when no long-press is detected, so single-tap continues to
 * route to the in-document handler.
 *
 * Touch slop and timeout values come from [androidx.compose.ui.platform.ViewConfiguration]
 * via the pointer-input scope, so they match platform conventions.
 */
fun Modifier.longPressBeforeChild(
    onLongPress: () -> Unit,
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val callback by rememberUpdatedState(onLongPress)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            val downId = down.id
            val downPosition: Offset = down.position
            var firedLongPress = false
            try {
                withTimeout(viewConfiguration.longPressTimeoutMillis) {
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change: PointerInputChange =
                            event.changes.firstOrNull { it.id == downId } ?: return@withTimeout
                        if (!change.pressed) return@withTimeout
                        if ((change.position - downPosition).getDistance() > viewConfiguration.touchSlop) {
                            return@withTimeout
                        }
                        pressed = change.pressed
                    }
                }
            } catch (_: PointerEventTimeoutCancellationException) {
                callback()
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                firedLongPress = true
            }
            if (firedLongPress) {
                // Drain remaining changes for this gesture so a
                // late release at the same position doesn't ALSO
                // bubble through to the player as an unrelated tap.
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) break
                }
            }
        }
    }
}
