@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.contentDescription
import androidx.compose.remote.creation.compose.modifier.drawWithContent
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.semantics
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.floor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp

// A rugged digital watch's LCD window: big italic seven-segment hours and minutes with every unlit
// segment ghosted behind them ("88:88"), small seconds in the accent sitting on the baseline, all
// in
// a recessed window over a fine mesh. Colours come from the theme — the window is the dashboard
// colour, lit segments the primary text, seconds the accent — so the positive (light) display is
// black segments on grey and the negative (dark) one white on black. Every digit reads the
// player's clock, so the face ticks without a re-encode.

/**
 * The wordmark a large LCD-window clock sets under its date when the card has no title of its own.
 * Rugged Digital is a one-off made with Greg Bullock, the designer of the GShark watch face it
 * comes from; G-SPOT is that face's riff on a sports-watch maker's mark.
 */
internal const val LcdWindowWordmark = "G-SPOT"

/** How far a digit's top leans right of its foot, as a share of its height. */
private const val Slant = 0.11f

/** A digit cell's width over its height. */
private const val CellAspect = 0.6f

/** The face's geometry in units of the window's height. */
private object Face {
  const val Pad = 0.09f
  const val Main = 1f - 2f * Pad
  const val Cell = Main * CellAspect
  const val Gap = Cell * 0.2f
  const val Colon = Cell * 0.36f
  const val Small = Main * 0.46f
  const val SmallCell = Small * CellAspect
  const val SmallGap = SmallCell * 0.22f
  const val MainWidth = 4f * Cell + 2f * Gap + Colon + Main * Slant
  const val SecondsWidth = 0.14f + 2f * SmallCell + SmallGap + Small * Slant

  fun aspect(seconds: Boolean) = 2f * Pad + MainWidth + if (seconds) SecondsWidth else 0f
}

/**
 * The time in an LCD window: italic segment digits with ghosts, accent seconds, in a window
 * [heightDp] tall (the presentation's: a Wear Small slot's compact clock as well as a card's).
 */
@Composable
@RemoteComposable
internal fun LcdWindowClock(data: HaClockData, timeLabel: RemoteString, heightDp: Int) {
  val frozen = data.staticTimeLabel?.let { parseClockLabel(it, data.use24Hour) }
  // Preserve the public static-label override even when it isn't a numeric time.
  if (data.staticTimeLabel != null && frozen == null) {
    RemoteText(
      text = timeLabel,
      color = haTheme().primaryText,
      fontSize = HaType.Display,
      style = haTextStyle(),
    )
    return
  }
  val theme = haTheme()
  val offset =
    if (data.zoned) cardFloat("zone_offset_minutes", data.zoneOffsetMinutes.toFloat()) else 0f.rf
  val total =
    RemoteFloat(RcProtocol.FLOAT_TIME_IN_HR) * 60f +
      RemoteFloat(RcProtocol.FLOAT_TIME_IN_MIN).rem(60f) +
      offset +
      1_440_000f.rf
  val hour24 = frozen?.hour?.toFloat()?.rf ?: floor(total.rem(1440f) / 60f)
  val minute = frozen?.minute?.toFloat()?.rf ?: total.rem(60f)
  val second = frozen?.second?.toFloat()?.rf ?: RemoteFloat(RcProtocol.FLOAT_TIME_IN_SEC).rem(60f)
  val hour =
    if (data.use24Hour) hour24 else hour24.rem(12f).isEqualTo(0f.rf).select(12f.rf, hour24.rem(12f))
  val main = listOf(floor(hour / 10f), hour.rem(10f), floor(minute / 10f), minute.rem(10f))
  val seconds = listOf(floor(second / 10f), second.rem(10f))
  // Under the kit's own palette, ghosts are opaque blends over the window, so a segment's bar and
  // tips can overlap without darkening where they meet. On a host's surfaces (a launcher widget in
  // system colours) the window and segments are the host's runtime roles, so the ghosts are
  // translucent copies of those roles and follow the host's colours too.
  val active = currentHaUberTheme()
  val palette = active.theme.palette(active.darkTheme)
  val lit = theme.primaryText
  val accent = theme.linkText
  val ghost =
    if (active.ownPalette) lerp(palette.dashboardBackground, palette.primaryText, 0.12f).rc
    else theme.primaryText.copy(alpha = 0.12f.rf)
  val accentGhost =
    if (active.ownPalette) lerp(palette.dashboardBackground, palette.linkText, 0.18f).rc
    else theme.linkText.copy(alpha = 0.18f.rf)
  RemoteColumn(horizontalAlignment = RemoteAlignment.CenterHorizontally) {
    RemoteCanvas(
      RemoteModifier.semantics { contentDescription = timeLabel }
        .fillMaxWidth()
        .height(heightDp.rdp)
    ) {
      val box = FitBox(width, height, Face.aspect(data.showSeconds))
      val x = box.left
      val y = box.top
      val w = box.width
      val h = box.height
      // The window: a bevel in the divider, the recess in the dashboard colour, a shadow under its
      // top edge, and a fine mesh behind the glass.
      fillRound(theme.divider, x, y, w, h, h * 0.1f.rf)
      fillRound(
        theme.dashboardBackground,
        x + h * 0.025f.rf,
        y + h * 0.025f.rf,
        w - h * 0.05f.rf,
        h * 0.95f.rf,
        h * 0.08f.rf,
      )
      fillRound(
        Color.Black.copy(alpha = if (palette.isDark) 0.28f else 0.1f).rc,
        x + h * 0.04f.rf,
        y + h * 0.025f.rf,
        w - h * 0.08f.rf,
        h * 0.03f.rf,
        h * 0.015f.rf,
      )
      val mesh = fillPaint(theme.divider.copy(alpha = 0.55f.rf))
      val rows = 9
      val cols = (Face.aspect(data.showSeconds) * rows).toInt()
      for (r in 0 until rows) for (c in 0 until cols) {
        val stagger = if (r % 2 == 0) 0f else 0.5f
        drawCircle(
          mesh,
          h * 0.012f.rf,
          RemoteOffset(
            x + w * ((c + 0.5f + stagger) / (cols + 0.5f)).rf,
            y + h * ((r + 0.75f) / (rows + 0.5f)).rf,
          ),
        )
      }
      val top = y + h * Face.Pad.rf
      var cx = x + h * Face.Pad.rf
      main.forEachIndexed { i, digit ->
        segmentDigit(digit, cx, top, h * Face.Main.rf, lit, ghost)
        cx = cx + h * Face.Cell.rf
        if (i == 1) {
          colon(cx + h * (Face.Gap * 0.5f).rf, top, h * Face.Colon.rf, h * Face.Main.rf, lit)
          cx = cx + h * (Face.Gap + Face.Colon).rf
        } else if (i != 3) cx = cx + h * Face.Gap.rf
      }
      if (data.showSeconds) {
        val sTop = top + h * (Face.Main - Face.Small).rf
        cx = cx + h * (0.14f + Face.Main * Slant).rf
        seconds.forEachIndexed { i, digit ->
          segmentDigit(digit, cx, sTop, h * Face.Small.rf, accent, accentGhost)
          cx = cx + h * (Face.SmallCell + if (i == 0) Face.SmallGap else 0f).rf
        }
      }
    }
    if (!data.use24Hour)
      RemoteText(
        text = hour24.isLessThan(12f.rf).select("AM".rs, "PM".rs),
        color = theme.primaryText,
        fontSize = HaType.Micro,
        style = haTextStyle(),
      )
  }
}

/** A segment's thickness, as a share of the digit's height. */
private const val SegmentT = 0.1f

/** The gap between two segments' tips, as a share of the digit's height. */
private const val SegmentGap = 0.018f

/**
 * One segment in digit-height units: its two tips (italic slant applied), the unit direction from
 * the first to the second, and its angle in degrees. Static, since a cell is always [CellAspect]
 * wide for its height.
 */
private class SegmentShape(x0: Float, y0: Float, x1: Float, y1: Float) {
  private fun lean(x: Float, y: Float) = x + Slant * (1f - y)

  val ax: Float
  val ay: Float
  val bx: Float
  val by: Float
  val dx: Float
  val dy: Float
  val degrees: Float

  init {
    val sx = lean(x0, y0)
    val ex = lean(x1, y1)
    val len = kotlin.math.sqrt((ex - sx) * (ex - sx) + (y1 - y0) * (y1 - y0))
    dx = (ex - sx) / len
    dy = (y1 - y0) / len
    ax = sx + dx * SegmentGap
    ay = y0 + dy * SegmentGap
    bx = ex - dx * SegmentGap
    by = y1 - dy * SegmentGap
    degrees = (kotlin.math.atan2(dy, dx) * 180f / kotlin.math.PI.toFloat())
  }
}

/** A..G on the digit frame: tips meet at the corners, as on a watch's LCD. */
private val SegmentShapes: List<SegmentShape> = run {
  val w = CellAspect
  val h = SegmentT / 2f
  listOf(
    SegmentShape(h, h, w - h, h),
    SegmentShape(w - h, h, w - h, 0.5f),
    SegmentShape(w - h, 0.5f, w - h, 1f - h),
    SegmentShape(h, 1f - h, w - h, 1f - h),
    SegmentShape(h, 0.5f, h, 1f - h),
    SegmentShape(h, h, h, 0.5f),
    SegmentShape(h, 0.5f, w - h, 0.5f),
  )
}

/**
 * One italic seven-segment digit [digit], [ch] tall, at ([x], [y]). Each segment is a bar with a
 * pointed tip at either end (a 45° diamond), the shape of an LCD's segment electrodes; unlit ones
 * draw in [ghost].
 */
private fun RemoteDrawScope.segmentDigit(
  digit: RemoteFloat,
  x: RemoteFloat,
  y: RemoteFloat,
  ch: RemoteFloat,
  lit: RemoteColor,
  ghost: RemoteColor,
) {
  val t = SegmentT
  val diamond = t / kotlin.math.sqrt(2f)
  SegmentShapes.forEachIndexed { segment, s ->
    var ink = ghost
    ConsoleDigitSegments.forEachIndexed { n, mask ->
      if (mask and (1 shl segment) != 0) ink = digit.isEqualTo(n.toFloat().rf).select(lit, ink)
    }
    val fill = fillPaint(ink)
    // The bar between the two tips' diamonds.
    drawLine(
      segmentPaint(ch * t.rf, ink),
      RemoteOffset(x + ch * (s.ax + s.dx * t / 2f).rf, y + ch * (s.ay + s.dy * t / 2f).rf),
      RemoteOffset(x + ch * (s.bx - s.dx * t / 2f).rf, y + ch * (s.by - s.dy * t / 2f).rf),
    )
    for ((tx, ty, sign) in listOf(Triple(s.ax, s.ay, 1f), Triple(s.bx, s.by, -1f))) {
      val cx = x + ch * (tx + sign * s.dx * t / 2f).rf
      val cy = y + ch * (ty + sign * s.dy * t / 2f).rf
      rotate((s.degrees + 45f).rf, RemoteOffset(cx, cy)) {
        drawRect(
          fill,
          RemoteOffset(cx - ch * (diamond / 2f).rf, cy - ch * (diamond / 2f).rf),
          androidx.compose.remote.creation.compose.layout.RemoteSize(
            ch * diamond.rf,
            ch * diamond.rf,
          ),
        )
      }
    }
  }
}

/** The hours-minutes colon: two leaning square dots. */
private fun RemoteDrawScope.colon(
  x: RemoteFloat,
  y: RemoteFloat,
  cw: RemoteFloat,
  ch: RemoteFloat,
  color: RemoteColor,
) {
  val dot = ch * 0.1f.rf
  for (fy in listOf(0.3f, 0.7f)) {
    fillRound(
      color,
      x + (cw - dot) / 2f.rf + ch * (Slant * (1f - fy)).rf,
      y + ch * fy.rf - dot / 2f.rf,
      dot,
      dot,
      dot * 0.15f.rf,
    )
  }
}

private fun segmentPaint(width: RemoteFloat, color: RemoteColor) = RemotePaint {
  isAntiAlias = true
  style = PaintingStyle.Stroke
  strokeWidth = width
  strokeCap = StrokeCap.Butt
}
  .apply { this.color = color }

/**
 * A rugged watch's complication window as a key: a square recessed tile in the window colour, with
 * a bevelled rim, a shadow under its top edge and the same fine mesh as the time's window.
 *
 * The tile reaches [KeyOutset] past the key's bounds rather than padding them, so a caller that
 * sizes its key (a to-do checkbox, a mode segment) keeps its content at full size. A selected key
 * keeps the caller's accent fill under it: the tile draws only its rim, so the caller's foreground
 * stays legible on its own background.
 */
internal object LcdWindowKey : HaKeyRenderer {
  /** How far the tile reaches past the key, in document pixels. */
  private const val KeyOutset = 9f

  @Composable
  override fun chrome(accent: RemoteColor?, selected: Boolean?): RemoteModifier {
    val theme = haTheme()
    val dark = currentHaUberTheme().let { it.theme.palette(it.darkTheme).isDark }
    return RemoteModifier.drawWithContent {
      val o = KeyOutset.rf
      val x = 0f.rf - o
      val y = 0f.rf - o
      val w = width + o * 2f.rf
      val h = height + o * 2f.rf
      val r = 9f.rf
      if (selected == true) {
        drawRoundRect(
          strokePaint(2f, accent ?: theme.linkText, round = false),
          RemoteOffset(x + 1f.rf, y + 1f.rf),
          androidx.compose.remote.creation.compose.layout.RemoteSize(w - 2f.rf, h - 2f.rf),
          RemoteOffset(r, r),
        )
      } else {
        fillRound(theme.divider, x, y, w, h, r)
        fillRound(theme.dashboardBackground, x + 1.5f.rf, y + 1.5f.rf, w - 3f.rf, h - 3f.rf, r)
        fillRound(
          Color.Black.copy(alpha = if (dark) 0.3f else 0.1f).rc,
          x + 4f.rf,
          y + 1.5f.rf,
          w - 8f.rf,
          2.5f.rf,
          1.2f.rf,
        )
        val mesh = fillPaint(theme.divider.copy(alpha = 0.6f.rf))
        val n = 7
        for (row in 0 until n) for (col in 0 until n) {
          drawCircle(
            mesh,
            0.9f.rf,
            RemoteOffset(
              x + w * ((col + if (row % 2 == 0) 0.5f else 1f) / (n + 0.5f)).rf,
              y + h * ((row + 0.6f) / (n + 0.2f)).rf,
            ),
          )
        }
      }
      drawContent()
    }
  }
}
