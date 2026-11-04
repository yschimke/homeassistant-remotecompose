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
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxHeight
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
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
 *
 * [cellsPerRow] is how many cells share a row where FlowLayout can't wrap them: a narrow widget
 * gives each cell the width its name needs rather than crushing three into it. [compactCells] draws
 * each cell as its icon over its state, without the name: the two lines a Wear Small slot holds.
 */
@Composable
@RemoteComposable
fun RemoteHaGlance(
  data: HaGlanceData,
  modifier: RemoteModifier = RemoteModifier,
  fillHeight: Boolean = false,
  cellsPerRow: Int = CompactRowMaxCells,
  compactCells: Boolean = false,
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
        .padding(horizontal = 12.rdp, vertical = (if (compactCells) 2 else 10).rdp)
  ) {
    // When the host pins extra height (fillHeight) the title and grid centre together in it, so a
    // short grid isn't left under a title on the top edge (Principle 8). Centring lives in this
    // column's own arrangement: a wrapper alignment is a no-op for a wrap-height child (#224).
    RemoteColumn(
      modifier = if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier,
      verticalArrangement = if (fillHeight) RemoteArrangement.Center else RemoteArrangement.Top,
    ) {
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
      CellGrid(supportsFlow, cells, cellsPerRow, compactCells)
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
private fun CellGrid(
  supportsFlow: Boolean,
  cells: List<HaGlanceCellData>,
  cellsPerRow: Int,
  compact: Boolean,
) {
  if (supportsFlow) {
    RemoteFlowRow(
      modifier = RemoteModifier.fillMaxWidth(),
      horizontalArrangement = RemoteArrangement.SpaceEvenly,
      verticalArrangement = RemoteArrangement.spacedBy(6.rdp),
    ) {
      cells.forEach { cell -> RemoteHaGlanceCell(cell, compact = compact) }
    }
  } else {
    RemoteColumn(
      modifier = RemoteModifier.fillMaxWidth(),
      verticalArrangement = RemoteArrangement.spacedBy(6.rdp),
    ) {
      cells.chunked(cellsPerRow.coerceAtLeast(1)).forEach { row ->
        RemoteRow(
          modifier = RemoteModifier.fillMaxWidth(),
          horizontalArrangement = RemoteArrangement.SpaceEvenly,
          verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
          // An equal share each, so a long name ellipsizes within its cell instead of running
          // into its neighbour's.
          row.forEach { cell ->
            RemoteHaGlanceCell(cell, RemoteModifier.weight(1f), compact = compact)
          }
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
fun RemoteHaGlanceCell(
  data: HaGlanceCellData,
  modifier: RemoteModifier = RemoteModifier,
  compact: Boolean = false,
) {
  val theme = haTheme()
  val clickable =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  val look = entityLook(data.entityId, data.accent)
  val accent: RemoteColor = look.accent
  if (haLayouts().glanceComplication && !compact) {
    GlanceComplication(data, look, clickable, modifier)
    return
  }
  RemoteColumn(
    modifier =
      modifier
        .then(clickable)
        .padding(horizontal = HaSpace.XXS.rdp, vertical = (if (compact) 0 else 4).rdp),
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
  ) {
    // One line each: a cell narrower than its name ellipsizes rather than stacking letters.
    if (!compact) {
      RemoteText(
        text = entityName(data.entityId, data.name),
        color = look.dim(theme.primaryText),
        fontSize = HaType.Label,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    RemoteBox(
      modifier =
        RemoteModifier.padding(vertical = (if (compact) 0 else 4).rdp)
          .then(if (data.tapAction != HaAction.None) haKeyChrome(accent) else RemoteModifier)
    ) {
      HaStateIcon(
        icon = data.icon,
        accent = data.accent,
        look = look,
        tint = accent,
        // Decorative: the name is drawn above it.
        contentDescription = null,
        modifier = RemoteModifier.size((if (compact) 22 else 28).rdp),
      )
    }
    RemoteText(
      text = data.state,
      color = look.dim(theme.secondaryText),
      fontSize = HaType.Caption,
      style = haTextStyle(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * A glance cell as a watch's complication window: the name over a small recessed LCD tile holding a
 * greyscale icon and the value. The icon is lit in the primary ink while the entity is on and dims
 * to the secondary when off, as an LCD segment does; the tile is the window colour, as the clock's.
 */
@Composable
@RemoteComposable
private fun GlanceComplication(
  data: HaGlanceCellData,
  look: HaEntityLook,
  clickable: RemoteModifier,
  modifier: RemoteModifier,
) {
  val theme = haTheme()
  val ink = look.isOn?.select(theme.primaryText, theme.secondaryText) ?: theme.primaryText
  RemoteColumn(
    modifier = modifier.then(clickable).padding(horizontal = HaSpace.XXS.rdp, vertical = 2.rdp),
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
  ) {
    RemoteText(
      text = entityName(data.entityId, data.name),
      color = look.dim(theme.secondaryText),
      fontSize = HaType.Caption,
      style = haTextStyle(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    RemoteColumn(
      modifier =
        RemoteModifier.padding(top = 3.rdp)
          .clip(RemoteRoundedCornerShape(8.rdp))
          .background(theme.dashboardBackground)
          .border(1.rdp, theme.divider, RemoteRoundedCornerShape(8.rdp))
          .padding(horizontal = 10.rdp, vertical = 5.rdp),
      horizontalAlignment = RemoteAlignment.CenterHorizontally,
    ) {
      HaStateIcon(
        icon = data.icon,
        accent = data.accent,
        look = look,
        tint = look.dim(ink),
        // Decorative: the name is drawn above it.
        contentDescription = null,
        modifier = RemoteModifier.size(20.rdp),
      )
      RemoteText(
        text = data.state,
        color = look.dim(theme.primaryText),
        fontSize = HaType.Caption,
        fontWeight = FontWeight.Bold,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
