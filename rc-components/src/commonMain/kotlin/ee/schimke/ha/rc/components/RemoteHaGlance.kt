@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteFlowRow
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteSize
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
import androidx.compose.remote.creation.compose.shaders.RemoteBrush
import androidx.compose.remote.creation.compose.shaders.linearGradient
import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.lerp
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
private const val TileW = 78f
private const val TileH = 90f
private const val WinX = 6f
private const val WinY = 6f
private const val WinW = 66f
private const val WinH = 78f
private const val WinR = 12f

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

/** One palette's window: Greg's chamber colours, in the dark (negative) and light display. */
private class WindowLook(
  val lit: Color,
  val shade: Color,
  val wafer: Color,
  val shadow: Color,
  val shadowGain: Float,
)

private val DarkWindow =
  WindowLook(
    lit = Color(150, 172, 198),
    shade = Color(3, 5, 8),
    wafer = Color(190, 215, 240).copy(alpha = 0.05f),
    shadow = Color.Black,
    shadowGain = 1f,
  )

private val LightWindow =
  WindowLook(
    lit = Color.White,
    shade = Color(70, 76, 84),
    wafer = Color(70, 82, 96).copy(alpha = 0.045f),
    shadow = Color(20, 26, 32),
    shadowGain = 0.5f,
  )

/**
 * The watch's complication window, drawn behind a cell's icon and value: Greg Bullock's GShark quad
 * chamber, after the face's own recipe (casino-gshark's quad-chamber generator), drawn as Remote
 * Compose ops in the face's units so it's crisp at any size and follows the theme's colours:
 * - a dark trench just outside the edge,
 * - the floor in the window colour under a faint honeycomb wafer,
 * - the lip's shadow falling into the window from its top and sides, and occlusion at its foot,
 * - the glass's faint anti-reflective sheen,
 * - the bevel lit from the upper left: the lower and right walls catch the light, the upper and
 *   left fall into shade.
 *
 * A toggle's window is backlit while it's on, a soft accent glow rising from the middle of its
 * floor like the watch's backlight, and dark while off; a reading's window is never lit.
 */
@Composable
private fun complicationWindow(isOn: RemoteBoolean?): RemoteModifier {
  val theme = haTheme()
  val dark = currentHaUberTheme().let { it.theme.palette(it.darkTheme).isDark }
  val look = if (dark) DarkWindow else LightWindow
  val clear = Color.Transparent.rc
  // The backlight, live: eight faint layers in the accent while on, so it brightens smoothly
  // toward the middle; nothing while off.
  val glow =
    List(8) { 0.045f }.map { a -> isOn?.select(theme.linkText.copy(alpha = a.rf), clear) ?: clear }
  fun shadow(alpha: Float) = look.shadow.copy(alpha = (alpha * look.shadowGain).coerceAtMost(0.9f))
  fun lit(alpha: Float) = look.lit.copy(alpha = alpha)
  fun shade(alpha: Float) = look.shade.copy(alpha = alpha)
  return RemoteModifier.drawWithContent {
    scale(width / TileW.rf, height / TileH.rf, RemoteOffset(0f.rf, 0f.rf)) {
      // The trench, then the floor.
      drawRoundRect(
        strokePaint(1.3f, Color.Black.copy(alpha = 0.45f).rc, round = false),
        RemoteOffset((WinX - 0.8f).rf, (WinY - 0.8f).rf),
        RemoteSize((WinW + 1.6f).rf, (WinH + 1.6f).rf),
        RemoteOffset((WinR + 0.8f).rf, (WinR + 0.8f).rf),
      )
      fillRound(theme.dashboardBackground, WinX.rf, WinY.rf, WinW.rf, WinH.rf, WinR.rf)
      // The wafer.
      val wall = strokePaint(0.8f, look.wafer.rc, round = false)
      for (seg in HoneycombWalls) {
        drawLine(wall, RemoteOffset(seg[0].rf, seg[1].rf), RemoteOffset(seg[2].rf, seg[3].rf))
      }
      // The backlight, brightest in the middle.
      glow.forEachIndexed { i, color ->
        val inset = 2f + i * 3.2f
        fillRound(
          color,
          (WinX + inset).rf,
          (WinY + inset).rf,
          (WinW - inset * 2f).rf,
          (WinH - inset * 2f).rf,
          (9f + i * 2f).rf,
        )
      }
      // The lip's shadow and the glass's sheen, each a gradient across the whole window that fades
      // out where it ends, so the window's own corners clip it.
      fun window(from: RemoteOffset, to: RemoteOffset, vararg stops: Pair<Float, Color>) =
        drawRoundRect(
          gradientPaint(from, to, stops.toList(), samples = 64),
          RemoteOffset(WinX.rf, WinY.rf),
          RemoteSize(WinW.rf, WinH.rf),
          RemoteOffset(WinR.rf, WinR.rf),
        )
      // Deepest under the top edge, softer down the left (the light's from the upper left), a
      // short fall down the right, and occlusion at the floor's foot.
      window(
        RemoteOffset(0f.rf, WinY.rf),
        RemoteOffset(0f.rf, (WinY + 13f).rf),
        0f to shadow(0.9f),
        0.3f to shadow(0.5f),
        0.6f to shadow(0.16f),
        1f to shadow(0f),
      )
      window(
        RemoteOffset(WinX.rf, 0f.rf),
        RemoteOffset((WinX + 9f).rf, 0f.rf),
        0f to shadow(0.6f),
        0.4f to shadow(0.22f),
        1f to shadow(0f),
      )
      window(
        RemoteOffset((WinX + WinW).rf, 0f.rf),
        RemoteOffset((WinX + WinW - 7f).rf, 0f.rf),
        0f to shadow(0.38f),
        0.45f to shadow(0.12f),
        1f to shadow(0f),
      )
      window(
        RemoteOffset(0f.rf, (WinY + WinH).rf),
        RemoteOffset(0f.rf, (WinY + WinH - 4f).rf),
        0f to shadow(0.28f),
        1f to shadow(0f),
      )
      // Two faint diagonal bands of the glass's anti-reflective coating.
      val sheen = Color(175, 228, 255)
      val s = if (dark) 0.03f else 0.022f
      window(
        RemoteOffset(WinX.rf, WinY.rf),
        RemoteOffset((WinX + 78f).rf, (WinY + 63.3f).rf),
        0.30f to sheen.copy(alpha = 0f),
        0.39f to sheen.copy(alpha = s),
        0.48f to sheen.copy(alpha = 0f),
        0.64f to sheen.copy(alpha = 0f),
        0.674f to sheen.copy(alpha = s * 0.66f),
        0.705f to sheen.copy(alpha = 0f),
      )
      // The bevel, on the window's edge: each side by how squarely it faces the light (from the
      // upper left), each corner graded between its sides.
      val b = 0.25f // the bevel's centre, just inside the edge
      val x0 = WinX + b
      val y0 = WinY + b
      val x1 = WinX + WinW - b
      val y1 = WinY + WinH - b
      val r = WinR - b
      val edge = 1.6f
      val top = shade(0.74f)
      val left = shade(0.58f)
      val right = lit(0.42f)
      val bottom = lit(0.58f)
      fun side(color: Color, ax: Float, ay: Float, bx: Float, by: Float) =
        drawLine(
          strokePaint(edge, color.rc, round = false),
          RemoteOffset(ax.rf, ay.rf),
          RemoteOffset(bx.rf, by.rf),
        )
      side(top, x0 + r, y0, x1 - r, y0)
      side(left, x0, y0 + r, x0, y1 - r)
      side(right, x1, y0 + r, x1, y1 - r)
      side(bottom, x0 + r, y1, x1 - r, y1)
      // A corner's quarter arc around (cx, cy) from [startDeg], its colour graded along the chord
      // from its first side to its second.
      fun corner(
        cx: Float,
        cy: Float,
        startDeg: Float,
        from: RemoteOffset,
        to: RemoteOffset,
        vararg stops: Pair<Float, Color>,
      ) {
        val paint = gradientPaint(from, to, stops.toList())
        paint.style = PaintingStyle.Stroke
        paint.strokeWidth = edge.rf
        drawArc(
          paint,
          startDeg.rf,
          90f.rf,
          false,
          RemoteOffset((cx - r).rf, (cy - r).rf),
          RemoteSize((r * 2f).rf, (r * 2f).rf),
        )
      }
      // Upper left: in shade throughout, deepest where it faces away from the light.
      corner(
        x0 + r,
        y0 + r,
        180f,
        RemoteOffset(x0.rf, (y0 + r).rf),
        RemoteOffset((x0 + r).rf, y0.rf),
        0f to left,
        0.5f to shade(0.95f),
        1f to top,
      )
      // Upper right: from the top's shade, through the turn, into the right side's light.
      corner(
        x1 - r,
        y0 + r,
        270f,
        RemoteOffset((x1 - r).rf, y0.rf),
        RemoteOffset(x1.rf, (y0 + r).rf),
        0f to top,
        0.55f to shade(0f),
        0.6f to lit(0f),
        1f to right,
      )
      // Lower right: lit throughout, brightest where it faces the light.
      corner(
        x1 - r,
        y1 - r,
        0f,
        RemoteOffset(x1.rf, (y1 - r).rf),
        RemoteOffset((x1 - r).rf, y1.rf),
        0f to right,
        0.5f to lit(0.72f),
        1f to bottom,
      )
      // Lower left: from the bottom's light, through the turn, into the left side's shade.
      corner(
        x0 + r,
        y1 - r,
        90f,
        RemoteOffset((x0 + r).rf, y1.rf),
        RemoteOffset(x0.rf, (y1 - r).rf),
        0f to bottom,
        0.42f to lit(0f),
        0.48f to shade(0f),
        1f to left,
      )
    }
    drawContent()
  }
}

/**
 * A fill paint holding a linear gradient from [from] to [to] through [stops] (position, colour),
 * sampled to [samples] evenly spaced colours: the one gradient form every player takes.
 */
private fun RemoteDrawScope.gradientPaint(
  from: RemoteOffset,
  to: RemoteOffset,
  stops: List<Pair<Float, Color>>,
  samples: Int = 24,
): RemotePaint {
  val colors =
    List(samples) { i ->
      val t = i / (samples - 1f)
      val next = stops.indexOfFirst { it.first >= t }
      when {
        next <= 0 -> stops[maxOf(next, 0)].second
        else -> {
          val (f0, c0) = stops[next - 1]
          val (f1, c1) = stops[next]
          lerp(c0, c1, if (f1 > f0) (t - f0) / (f1 - f0) else 1f)
        }
      }
    }
  return RemotePaint().also { paint ->
    paint.style = PaintingStyle.Fill
    paint.isAntiAlias = true
    with(RemoteBrush.linearGradient(colors.map { it.rc }, from, to)) { applyTo(paint, size) }
  }
}
