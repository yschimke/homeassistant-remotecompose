package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed

/**
 * Opens a watch card on a long press anywhere on it ([onLongPress]), or on a tap the card's own
 * document doesn't take ([onUnhandledTap]): outside its buttons, on a card with no tap of its own.
 *
 * It watches the card's pointer stream without consuming it, so the card's own clicks run as
 * before. The player's clickables consume a tap's release, so a release still unconsumed after the
 * card has seen it (the [PointerEventPass.Final] pass) is a tap nothing in the card handled. A
 * press that moves past the touch slop, or whose movement a scroll consumed, is neither; a press a
 * clickable consumed can still be a long press. A long press consumes the rest of its gesture
 * before the card sees it, so the click under the finger doesn't run as well.
 */
internal fun Modifier.wearCardGestures(
  onLongPress: () -> Unit,
  onUnhandledTap: () -> Unit,
): Modifier =
  pointerInput(onLongPress, onUnhandledTap) {
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      val press =
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
          awaitRelease(down, viewConfiguration.touchSlop)
        }
      when (press) {
        null -> {
          onLongPress()
          consumeUntilReleased()
        }
        CardPress.Unhandled -> onUnhandledTap()
        CardPress.Handled,
        CardPress.Cancelled -> Unit
      }
    }
  }

private enum class CardPress {
  /** Released with nothing in the card taking it. */
  Unhandled,
  /** Released onto one of the card's own clickables. */
  Handled,
  /** Moved, scrolled or lost. */
  Cancelled,
}

private suspend fun AwaitPointerEventScope.awaitRelease(
  down: PointerInputChange,
  touchSlop: Float,
): CardPress {
  while (true) {
    val change =
      awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id }
        ?: return CardPress.Cancelled
    if (change.changedToUpIgnoreConsumed()) {
      return if (change.isConsumed) CardPress.Handled else CardPress.Unhandled
    }
    // The card's own clickables consume the press itself, which must not stop a long press: only
    // movement a scroll took, or movement past the touch slop, cancels.
    if (change.changedToDownIgnoreConsumed()) continue
    val scrolled = change.isConsumed && change.positionChangeIgnoreConsumed() != Offset.Zero
    if (scrolled || (change.position - down.position).getDistance() > touchSlop) {
      return CardPress.Cancelled
    }
  }
}

private suspend fun AwaitPointerEventScope.consumeUntilReleased() {
  do {
    val event = awaitPointerEvent(PointerEventPass.Initial)
    event.changes.forEach { it.consume() }
  } while (event.changes.any { it.pressed })
}
