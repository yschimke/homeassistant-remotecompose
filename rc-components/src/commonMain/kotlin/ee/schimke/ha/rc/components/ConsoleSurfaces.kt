@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.drawWithContent
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.state.RemoteString
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
        fontWeight = RemoteFontWeight.Medium,
        style = haTextStyle(),
        maxLines = if (compact) 1 else 2,
        overflow = RemoteTextOverflow.Ellipsis,
      )
      if (detail != null)
        RemoteText(
          text = detail,
          color = Color(0xFF484D4E).rc,
          fontSize = HaType.Caption,
          style = haTextStyle(),
          maxLines = 2,
          overflow = RemoteTextOverflow.Ellipsis,
        )
    }
  }
}
