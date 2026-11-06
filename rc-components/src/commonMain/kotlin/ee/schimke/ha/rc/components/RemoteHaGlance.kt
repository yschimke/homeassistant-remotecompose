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
import androidx.compose.remote.creation.compose.modifier.widthIn
import androidx.compose.remote.creation.compose.state.RemoteBoolean
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
        .padding(horizontal = 12.rdp, vertical = (if (compactCells) 2 else 10).rdp),
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
          .then(if (data.tapAction != HaAction.None) haKeyChrome(accent) else RemoteModifier),
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
    // The window is the watch's: a fixed size at its own aspect (78 × 90 face units with its
    // edge), the icon and value centred in its floor.
    RemoteBox(
      modifier =
        RemoteModifier.padding(top = 3.rdp)
          .size(ComplicationWidth.rdp, ComplicationHeight.rdp)
          .then(complicationWindow(look.isOn)),
      contentAlignment = RemoteAlignment.Center,
    ) {
      RemoteColumn(horizontalAlignment = RemoteAlignment.CenterHorizontally) {
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
}

/** A complication window's size, at the face's window aspect (78 × 90 units with its edge). */
private const val ComplicationWidth = 60

private const val ComplicationHeight = 69

// The window in the face's own units (its 450px dial): a 66 × 78 window with 12-unit corners,
// 6 units in from each side of the 78 × 90 tile its trench and edge need.
private const val WinX = 6f
private const val WinY = 6f
private const val WinW = 66f
private const val WinH = ChamberWinH
private const val WinR = ChamberWinR

/** The wafer's honeycomb cell, vertex to vertex, in face units. */
private const val HexW = 10.8f

/** The window's signed distance (negative inside) at (x, y), in face units. */
private fun windowDistance(x: Float, y: Float): Float {
  val dx = kotlin.math.abs(x - (WinX + WinW / 2f)) - (WinW / 2f - WinR)
  val dy = kotlin.math.abs(y - (WinY + WinH / 2f)) - (WinH / 2f - WinR)
  val ox = kotlin.math.max(dx, 0f)
  val oy = kotlin.math.max(dy, 0f)
  return kotlin.math.sqrt(ox * ox + oy * oy) + kotlin.math.min(kotlin.math.max(dx, dy), 0f) - WinR
}

/**
 * The wafer: a flat-topped honeycomb over the window, as wall segments (x0, y0, x1, y1) in face
 * units, three walls a cell (the other three are its neighbours'), cut to the window here rather
 * than by a clip, so the players need no path.
 */
private val HoneycombWalls: List<FloatArray> = buildList {
  val w = HexW
  val h = w * 0.8660254f
  val inside = -0.3f
  fun cut(ax: Float, ay: Float, bx: Float, by: Float) {
    val aIn = windowDistance(ax, ay) <= inside
    val bIn = windowDistance(bx, by) <= inside
    if (!aIn && !bIn) return
    if (aIn && bIn) {
      add(floatArrayOf(ax, ay, bx, by))
      return
    }
    // One end is out: find where the wall meets the window's edge.
    var lo = 0f
    var hi = 1f
    repeat(16) {
      val t = (lo + hi) / 2f
      val d = windowDistance(ax + (bx - ax) * t, ay + (by - ay) * t)
      if ((d <= inside) == aIn) lo = t else hi = t
    }
    val ex = ax + (bx - ax) * lo
    val ey = ay + (by - ay) * lo
    if (aIn) add(floatArrayOf(ax, ay, ex, ey)) else add(floatArrayOf(ex, ey, bx, by))
  }
  val cols = ((WinX + WinW) / (w * 0.75f)).toInt() + 2
  val rows = ((WinY + WinH) / h).toInt() + 2
  for (c in 0..cols) for (r in -1..rows) {
    val cx = c * w * 0.75f + w / 2f
    val cy = r * h + h / 2f + if (c % 2 == 1) h / 2f else 0f
    cut(cx - w / 4f, cy + h / 2f, cx - w / 2f, cy)
    cut(cx - w / 2f, cy, cx - w / 4f, cy - h / 2f)
    cut(cx - w / 4f, cy - h / 2f, cx + w / 4f, cy - h / 2f)
  }
}

/**
 * The watch's complication window, drawn behind a cell's icon and value: the display window
 * ([lcdChamber]) at the face's own size, with its honeycomb wafer. A toggle's window is backlit
 * while it's on and dark while off; a reading's window is never lit.
 */
@Composable
private fun complicationWindow(isOn: RemoteBoolean?): RemoteModifier =
  lcdChamber(isOn = isOn, padUnits = WinX, wafer = HoneycombWalls)
