@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.drawWithContent
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * Shared tactile key mount for every Console command, including text keys and compact controls.
 * Applied inside the existing target so its action, semantics and hit area remain with the caller.
 * A bound [accent] keeps live state/selection visible on the rim. Other themes are untouched.
 */
@Composable
internal fun consoleKeyChrome(
  accent: RemoteColor? = null,
  selected: Boolean? = null,
): RemoteModifier {
  if (visualFamily() != HaVisualFamily.Console) return RemoteModifier
  return RemoteModifier.drawWithContent {
    val edge = width.min(height) * 0.045f.rf
    val depth = edge + 1f.rf
    fillRound(ConsoleSlot.rc, 0f.rf, 0f.rf, width, height, 3f.rf)
    drawRoundRect(
      metalPaint(0f.rf, height),
      RemoteOffset(0f.rf, 0f.rf),
      RemoteSize(width, height - depth),
      RemoteOffset(3f.rf, 3f.rf),
    )
    drawRoundRect(
      consoleCapPaint(0f.rf, height),
      RemoteOffset(edge, edge),
      RemoteSize(width - edge * 2f.rf, height - depth - edge * 2f.rf),
      RemoteOffset(2f.rf, 2f.rf),
    )
    if (accent != null)
      drawRoundRect(
        strokePaint(1f, accent),
        RemoteOffset(edge, edge),
        RemoteSize(width - edge * 2f.rf, height - depth - edge * 2f.rf),
        RemoteOffset(2f.rf, 2f.rf),
      )
    if (selected != null) {
      val lamp = RemoteOffset(width - 7f.rf, 6f.rf)
      drawCircle(fillPaint(ConsoleSlotLip.rc), 3f.rf, lamp)
      drawCircle(
        fillPaint(if (selected) accent ?: Color(0xFFF2A93B).rc else ConsoleSlot.rc),
        2f.rf,
        lamp,
      )
    }
    drawContent()
  }
}

/**
 * White service tape on the panel. The message can be a live binding, including a template label. A
 * small dark adhesive shadow, straight-cut paper edges and a warm paper sheen distinguish it from a
 * control. It never owns an action or intercepts taps.
 */
@Composable
@RemoteComposable
internal fun ConsoleTapeLabel(
  message: RemoteString,
  modifier: RemoteModifier = RemoteModifier,
  detail: RemoteString? = null,
  compact: Boolean = false,
) {
  val paper =
    RemoteModifier.drawWithContent {
        fillRect(Color.Black.copy(alpha = 0.3f).rc, 1f.rf, 2f.rf, width - 1f.rf, height - 2f.rf)
        fillRect(Color(0xFFFFFEF7).rc, 0f.rf, 0f.rf, width - 1f.rf, height - 2f.rf)
        fillRect(Color(0xFFE7E7DB).rc, 0f.rf, height - 3f.rf, width - 1f.rf, 1f.rf)
        // Fibres at the cut edges: short, irregular pale strands, like a torn tape roll.
        for (i in 0..5) {
          val y = (height - 4f.rf) * (i / 6f).rf
          fillRect(Color(0xFFDBDCD2).rc, 0f.rf, y, (if (i % 2 == 0) 2f else 1f).rf, 1f.rf)
          fillRect(Color(0xFFDBDCD2).rc, width - 2f.rf, y + 2f.rf, 1f.rf, 1f.rf)
        }
        drawContent()
      }
      .padding(horizontal = (if (compact) 4 else 9).rdp, vertical = (if (compact) 3 else 6).rdp)
  RemoteBox(modifier = modifier.then(paper), contentAlignment = RemoteAlignment.CenterStart) {
    RemoteColumn {
      RemoteText(
        text = message,
        color = Color(0xFF252A2B).rc,
        fontSize = if (compact) HaType.Micro else HaType.Label,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
        maxLines = if (compact) 1 else 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (detail != null)
        RemoteText(
          text = detail,
          color = Color(0xFF484D4E).rc,
          fontSize = HaType.Caption,
          style = haTextStyle(),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
    }
  }
}
