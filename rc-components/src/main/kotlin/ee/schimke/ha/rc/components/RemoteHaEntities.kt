@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * HA `entities` card — stacked list of [RemoteHaEntityRow]s with an optional title header. Matches
 * `hui-entities-card.ts` visually.
 *
 * [dense] tightens each row's vertical padding (6dp to 4dp) for a fixed widget canvas, where the
 * list has a set height to fit its rows into rather than growing to them.
 */
@Composable
@RemoteComposable
fun RemoteHaEntities(
  data: HaEntitiesData,
  modifier: RemoteModifier = RemoteModifier,
  dense: Boolean = false,
) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 12.rdp, vertical = 10.rdp)
  ) {
    RemoteColumn(horizontalAlignment = RemoteAlignment.Start) {
      if (data.title != null) {
        RemoteText(
          text = cardTitle(data.title),
          color = theme.primaryText,
          fontSize = adaptiveTitleSize(data.title),
          fontWeight = FontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        RemoteBox(modifier = RemoteModifier.padding(top = 4.rdp))
      }
      data.rows.forEach { row ->
        RemoteHaEntityRow(row, verticalPaddingDp = if (dense) DenseRowPaddingDp else 6)
      }
    }
  }
}

private const val DenseRowPaddingDp = 4
