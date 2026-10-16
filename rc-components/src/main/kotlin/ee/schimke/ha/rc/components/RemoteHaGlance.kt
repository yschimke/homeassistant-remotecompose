@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteFlowRow
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.fillMaxHeight
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * Cells per compatible row. Compact Wear slots keep only this many cells; launcher widgets retain
 * every cell across multiple rows, even when their decoder cannot read FlowLayout.
 */
private const val CompactRowMaxCells = 3

/**
 * HA `glance` card — title header over a flow-row of compact entity cells. Matches
 * `hui-glance-card.ts` visually.
 *
 * When [fillHeight] is set (Fixed-mode widget / Wear slot, where the host pins a canvas taller than
 * one row of cells) the card fills the whole cell and distributes the cell grid down it — a single
 * row centres, extra rows space out to fill — instead of gluing one row to the top over a blank
 * half-cell (Principle 8, "no dead space"). The title is retained in this tier (cheap P2) rather
 * than dropped.
 */
@Composable
@RemoteComposable
fun RemoteHaGlance(
  data: HaGlanceData,
  modifier: RemoteModifier = RemoteModifier,
  fillHeight: Boolean = false,
) {
  val theme = haTheme()
  val supportsFlow = LocalSupportsFlowLayout.current
  val cells =
    if (LocalCompactEntityLayout.current) data.cells.take(CompactRowMaxCells) else data.cells
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(if (fillHeight) RemoteModifier.fillMaxHeight() else RemoteModifier)
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 12.rdp, vertical = 10.rdp)
  ) {
    RemoteColumn(modifier = if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier) {
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
        RemoteBox(modifier = RemoteModifier.padding(top = 6.rdp))
      }
      // The cell grid occupies the rest of the cell. When the host
      // pins extra height (fillHeight) wrap the grid in a weighted
      // column that claims the leftover space and centres its
      // content — so a short grid sits in the middle of the cell
      // rather than glued under the title (Principle 8). Centring
      // has to live inside this weighted filler that arranges its
      // own child; a wrapper alignment is a no-op for a wrap-height
      // child in alpha010 (#224, Known gap 6 — same reason the
      // arc-dial Wide row fills and self-centres internally).
      if (fillHeight) {
        RemoteColumn(
          modifier = RemoteModifier.fillMaxWidth().weight(1f),
          verticalArrangement = RemoteArrangement.Center,
        ) {
          CellGrid(supportsFlow, cells)
        }
      } else {
        CellGrid(supportsFlow, cells)
      }
    }
  }
}

/**
 * The row/grid of glance cells. HA's glance spaces cells evenly across the card width.
 * [RemoteFlowRow] handles wrapping when more cells arrive than fit the row; within each row, cells
 * are distributed [SpaceEvenly][RemoteArrangement.SpaceEvenly] rather than packed to the start. On
 * surfaces whose capture profile rejects FlowLayout, use a column of ordinary [RemoteRow]s. Wear
 * callers explicitly cap the cell count; launcher callers retain all rows.
 */
@Composable
@RemoteComposable
private fun CellGrid(supportsFlow: Boolean, cells: List<HaGlanceCellData>) {
  if (supportsFlow) {
    RemoteFlowRow(
      modifier = RemoteModifier.fillMaxWidth(),
      horizontalArrangement = RemoteArrangement.SpaceEvenly,
      verticalArrangement = RemoteArrangement.spacedBy(6.rdp),
    ) {
      cells.forEach { cell -> RemoteHaGlanceCell(cell) }
    }
  } else {
    RemoteColumn(
      modifier = RemoteModifier.fillMaxWidth(),
      verticalArrangement = RemoteArrangement.spacedBy(6.rdp),
    ) {
      cells.chunked(CompactRowMaxCells).forEach { row ->
        RemoteRow(
          modifier = RemoteModifier.fillMaxWidth(),
          horizontalArrangement = RemoteArrangement.SpaceEvenly,
          verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
          row.forEach { cell -> RemoteHaGlanceCell(cell) }
        }
      }
    }
  }
}

/**
 * One cell inside a Glance — name on top, icon in the middle, state below. Matches HA's
 * `hui-glance-card.ts` cell order.
 */
@Composable
@RemoteComposable
fun RemoteHaGlanceCell(data: HaGlanceCellData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  val clickable =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  val isOnBinding =
    if (data.accent.toggleable) LiveValues.isOn(data.entityId, data.accent.initiallyOn) else null
  val accent: RemoteColor =
    isOnBinding?.select(data.accent.themed().activeAccent, data.accent.themed().inactiveAccent)
      ?: data.accent.themed().activeAccent
  RemoteColumn(
    modifier = modifier.then(clickable).padding(vertical = 4.rdp),
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
  ) {
    RemoteText(
      text = entityName(data.entityId, data.name),
      color = theme.primaryText,
      fontSize = HaType.Label,
      style = haTextStyle(),
    )
    RemoteBox(modifier = RemoteModifier.padding(vertical = 4.rdp)) {
      HaStateIcon(
        icon = data.icon,
        activeIcon = data.accent.activeIcon,
        entityId = data.entityId,
        activeWhenUnavailable = data.accent.activeIconWhenUnavailable,
        initiallyUnavailable = data.accent.initiallyUnavailable,
        initiallyOn = data.accent.initiallyOn,
        isOn = isOnBinding,
        tint = accent,
        contentDescription = entityName(data.entityId, data.name),
        modifier = RemoteModifier.size(28.rdp),
      )
    }
    RemoteText(
      text = data.state,
      color = theme.secondaryText,
      fontSize = HaType.Caption,
      style = haTextStyle(),
    )
  }
}
