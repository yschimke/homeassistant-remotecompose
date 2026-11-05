@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteFlowRow
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.drawWithContent
import androidx.compose.remote.creation.compose.modifier.fillMaxHeight
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.modifier.widthIn
import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
  // Lit while on, as an LCD's backlit segment; dim while off; a reading is always lit.
  val ink = look.isOn?.select(theme.primaryText, theme.secondaryText) ?: theme.primaryText
  RemoteColumn(
    // A 48dp-wide target however short the name and value.
    modifier =
      modifier
        .then(clickable)
        .widthIn(min = 48.rdp)
        .padding(horizontal = HaSpace.XXS.rdp, vertical = 2.rdp),
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
          .then(complicationWindow(look.isOn))
          .padding(horizontal = 10.rdp, vertical = 6.rdp),
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
        color = look.dim(ink),
        fontSize = HaType.Caption,
        fontWeight = FontWeight.Bold,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * The watch's complication window, drawn behind a cell's icon and value: a slightly recessed
 * rounded square in the window colour, shaded under its top edge, with a fine mesh floor and a lit
 * lip along its lower and right bevel, as the GShark face's own chambers are. A toggle's window is
 * backlit while it's on, an accent glow rising from its middle like the watch's backlight, and dark
 * when off; a reading's window is never lit. Drawn with plain fills only, so every player draws it.
 */
@Composable
private fun complicationWindow(isOn: RemoteBoolean?): RemoteModifier {
  val theme = haTheme()
  val dark = currentHaUberTheme().let { it.theme.palette(it.darkTheme).isDark }
  val shade = Color.Black.copy(alpha = if (dark) 0.55f else 0.22f).rc
  val lip = Color.White.copy(alpha = if (dark) 0.16f else 0.55f).rc
  val mesh = theme.divider.copy(alpha = 0.55f.rf)
  val clear = Color.Transparent.rc
  // The backlight, live: eight faint layers in the accent while on, so it brightens smoothly
  // toward the middle; nothing while off.
  val glow =
    List(8) { 0.045f }.map { a -> isOn?.select(theme.linkText.copy(alpha = a.rf), clear) ?: clear }
  return RemoteModifier.drawWithContent {
    val r = 9f.rf
    // The recess: the window, a dark rim above it, and the shadow its top edge throws.
    fillRound(shade, 0f.rf, 0f.rf, width, height, r)
    fillRound(theme.dashboardBackground, 1.5f.rf, 2.5f.rf, width - 3f.rf, height - 3.5f.rf, r)
    fillRound(shade, 3f.rf, 2.5f.rf, width - 6f.rf, 3f.rf, 1.5f.rf)
    // The mesh floor.
    val dots = fillPaint(mesh)
    val cols = 7
    val rows = 8
    for (row in 0 until rows) for (col in 0 until cols) {
      drawCircle(
        dots,
        0.9f.rf,
        RemoteOffset(
          width * ((col + if (row % 2 == 0) 0.5f else 1f) / (cols + 0.5f)).rf,
          height * ((row + 0.7f) / (rows + 0.4f)).rf,
        ),
      )
    }
    // The backlight, brightest in the middle.
    glow.forEachIndexed { i, color ->
      val inset = (2.5f + i * 3.2f).rf
      fillRound(
        color,
        inset,
        inset + 1f.rf,
        width - inset * 2f.rf,
        height - inset * 2f.rf - 1f.rf,
        r + (i * 2f).rf,
      )
    }
    // The bevel's lit lip along the lower and right edges.
    fillRound(lip, r, height - 1.5f.rf, width - r * 2f.rf, 1.2f.rf, 0.6f.rf)
    fillRound(lip, width - 1.8f.rf, r, 1.2f.rf, height - r * 2f.rf, 0.6f.rf)
    drawContent()
  }
}
