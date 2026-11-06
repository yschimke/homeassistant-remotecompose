@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.drawWithContent
import androidx.compose.remote.creation.compose.shaders.RemoteBrush
import androidx.compose.remote.creation.compose.shaders.linearGradient
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PaintingStyle

// Rugged Digital's display window: Greg Bullock's GShark quad chamber, after the face's own recipe
// (casino-gshark's quad-chamber generator), drawn as Remote Compose ops so it's crisp at any size
// and follows the theme's colours. Lengths are in the face's units (its 450px dial): a window 78
// units tall with 12-unit corners, scaled so it fills its box's height; its width is whatever the
// box gives it.

/** The window's height, in face units. */
internal const val ChamberWinH = 78f

/** The narrowest window, in face units, before a box's width sets its scale: the face's own. */
internal const val ChamberMinWinW = 66f

/** The window's corner radius, in face units. */
internal const val ChamberWinR = 12f

/**
 * A recessed LCD screen for an LCD-family part (a readout, a tile's screen, a heading strip): under
 * a kit that opts in ([HaLayouts.screenChamber], Rugged Digital) it's the watch's display window,
 * otherwise the flat recessed panel in the window colour with [cornerDp] corners. Content keeps its
 * own padding; the window draws behind it and doesn't clip it.
 */
@Composable
internal fun lcdScreen(cornerDp: Int = HaSpace.XXS): RemoteModifier =
  if (haLayouts().screenChamber) lcdChamber(padUnits = 2f)
  else
    RemoteModifier.clip(RemoteRoundedCornerShape(cornerDp.rdp))
      .background(haTheme().dashboardBackground)

/**
 * The watch's display window, drawn behind its content:
 * - a dark trench just outside the edge,
 * - the floor in the window colour, under the [wafer] if it has one (wall segments x0, y0, x1, y1
 *   in face units, already cut to the window),
 * - the lip's shadow falling into the window from its top and sides, and occlusion at its foot,
 * - the glass's faint anti-reflective sheen,
 * - the bevel lit from the upper left: the lower and right walls catch the light, the upper and
 *   left fall into shade.
 *
 * [padUnits] is the room around the window for its trench and edge. While [isOn] is true the window
 * is backlit, a soft accent glow rising from the middle of its floor like the watch's backlight;
 * with no [isOn] it's never lit.
 */
@Composable
internal fun lcdChamber(
  isOn: RemoteBoolean? = null,
  padUnits: Float = 6f,
  wafer: List<FloatArray>? = null,
): RemoteModifier {
  val theme = haTheme()
  val dark = currentHaUberTheme().let { it.theme.palette(it.darkTheme).isDark }
  val look = windowLook(dark)
  val clear = Color.Transparent.rc
  // The backlight, live: eight faint layers in the accent while on, so it brightens smoothly
  // toward the middle; nothing while off.
  val glow =
    if (isOn == null) emptyList()
    else List(8) { 0.045f }.map { a -> isOn.select(theme.linkText.copy(alpha = a.rf), clear) }
  fun shadow(alpha: Float) = look.shadow.at((alpha * look.shadowGain).coerceAtMost(0.9f))
  fun lit(alpha: Float) = look.lit.at(alpha)
  fun shade(alpha: Float) = look.shade.at(alpha)
  val p = padUnits
  return RemoteModifier.drawWithContent {
    // One face unit: the box's height over the face's window height, unless the box is narrower
    // than the face's window (a tall note), when its width sets it instead, so the corners and
    // edge never outgrow a narrow box. The window then fills the box both ways.
    val unit = (height / (ChamberWinH + p * 2f).rf).min(width / (ChamberMinWinW + p * 2f).rf)
    val ww = width / unit - (p * 2f).rf
    val wh = height / unit - (p * 2f).rf
    val wr = ChamberWinR.rf
    val x0w = p.rf
    val y0w = p.rf
    val x1w = x0w + ww
    val y1w = y0w + wh
    scale(unit, unit, RemoteOffset(0f.rf, 0f.rf)) {
      // The trench, then the floor.
      drawRoundRect(
        strokePaint(1.3f, Color.Black.copy(alpha = 0.45f).rc, round = false),
        RemoteOffset(x0w - 0.8f.rf, y0w - 0.8f.rf),
        RemoteSize(ww + 1.6f.rf, wh + 1.6f.rf),
        RemoteOffset(wr + 0.8f.rf, wr + 0.8f.rf),
      )
      fillRound(theme.dashboardBackground, x0w, y0w, ww, wh, wr)
      // The wafer.
      if (wafer != null) {
        val wall = strokePaint(0.8f, look.wafer.rc, round = false)
        for (seg in wafer) {
          drawLine(wall, RemoteOffset(seg[0].rf, seg[1].rf), RemoteOffset(seg[2].rf, seg[3].rf))
        }
      }
      // The backlight, brightest in the middle.
      glow.forEachIndexed { i, color ->
        val inset = (2f + i * 3.2f).rf
        fillRound(
          color,
          x0w + inset,
          y0w + inset,
          ww - inset * 2f.rf,
          wh - inset * 2f.rf,
          (9f + i * 2f).rf,
        )
      }
      // The lip's shadow and the glass's sheen, each a gradient across the whole window that fades
      // out where it ends, so the window's own corners clip it.
      fun window(from: RemoteOffset, to: RemoteOffset, vararg stops: Pair<Float, Ink>) =
        drawRoundRect(
          gradientPaint(from, to, stops.toList()),
          RemoteOffset(x0w, y0w),
          RemoteSize(ww, wh),
          RemoteOffset(wr, wr),
        )
      // Deepest under the top edge, softer down the left (the light's from the upper left), a
      // short fall down the right, and occlusion at the floor's foot.
      window(
        RemoteOffset(0f.rf, y0w),
        RemoteOffset(0f.rf, y0w + 13f.rf),
        0f to shadow(0.9f),
        0.3f to shadow(0.5f),
        0.6f to shadow(0.16f),
        1f to shadow(0f),
      )
      window(
        RemoteOffset(x0w, 0f.rf),
        RemoteOffset(x0w + 9f.rf, 0f.rf),
        0f to shadow(0.6f),
        0.4f to shadow(0.22f),
        1f to shadow(0f),
      )
      window(
        RemoteOffset(x1w, 0f.rf),
        RemoteOffset(x1w - 7f.rf, 0f.rf),
        0f to shadow(0.38f),
        0.45f to shadow(0.12f),
        1f to shadow(0f),
      )
      window(
        RemoteOffset(0f.rf, y1w),
        RemoteOffset(0f.rf, y1w - 4f.rf),
        0f to shadow(0.28f),
        1f to shadow(0f),
      )
      // Two faint diagonal bands of the glass's anti-reflective coating.
      val sheen = look.sheen
      val s = if (dark) 0.03f else 0.022f
      // Each band is its own short gradient along the diagonal, clear past its ends.
      val dx = ww * 1.18f.rf
      val dy = wh * (63.3f / ChamberWinH).rf
      fun along(t: Float) = RemoteOffset(x0w + dx * t.rf, y0w + dy * t.rf)
      window(
        along(0.30f),
        along(0.48f),
        0f to sheen.at(0f),
        0.5f to sheen.at(s),
        1f to sheen.at(0f),
      )
      window(
        along(0.64f),
        along(0.705f),
        0f to sheen.at(0f),
        0.52f to sheen.at(s * 0.66f),
        1f to sheen.at(0f),
      )
      // The bevel, on the window's edge: each side by how squarely it faces the light (from the
      // upper left), each corner graded between its sides.
      val b = 0.25f.rf // the bevel's centre, just inside the edge
      val x0 = x0w + b
      val y0 = y0w + b
      val x1 = x1w - b
      val y1 = y1w - b
      val r = wr - b
      val r2 = r * 2f.rf
      val edge = 1.6f
      val top = shade(0.74f)
      val left = shade(0.58f)
      val right = lit(0.42f)
      val bottom = lit(0.58f)
      fun side(color: Ink, ax: RemoteFloat, ay: RemoteFloat, bx: RemoteFloat, by: RemoteFloat) =
        drawLine(
          strokePaint(edge, color.rc, round = false),
          RemoteOffset(ax, ay),
          RemoteOffset(bx, by),
        )
      side(top, x0 + r, y0, x1 - r, y0)
      side(left, x0, y0 + r, x0, y1 - r)
      side(right, x1, y0 + r, x1, y1 - r)
      side(bottom, x0 + r, y1, x1 - r, y1)
      // A corner's quarter arc in the box at (left, top) from [startDeg], its colour graded along
      // the chord from its first side to its second.
      fun corner(
        left: RemoteFloat,
        top: RemoteFloat,
        startDeg: Float,
        from: RemoteOffset,
        to: RemoteOffset,
        vararg stops: Pair<Float, Ink>,
      ) {
        val paint = gradientPaint(from, to, stops.toList())
        paint.style = PaintingStyle.Stroke
        paint.strokeWidth = edge.rf
        drawArc(paint, startDeg.rf, 90f.rf, false, RemoteOffset(left, top), RemoteSize(r2, r2))
      }
      // Upper left: in shade throughout, deepest where it faces away from the light.
      corner(
        x0,
        y0,
        180f,
        RemoteOffset(x0, y0 + r),
        RemoteOffset(x0 + r, y0),
        0f to left,
        0.5f to shade(0.95f),
        1f to top,
      )
      // Upper right: from the top's shade, through the turn, into the right side's light.
      corner(
        x1 - r2,
        y0,
        270f,
        RemoteOffset(x1 - r, y0),
        RemoteOffset(x1, y0 + r),
        0f to top,
        0.55f to shade(0f),
        0.6f to lit(0f),
        1f to right,
      )
      // Lower right: lit throughout, brightest where it faces the light.
      corner(
        x1 - r2,
        y1 - r2,
        0f,
        RemoteOffset(x1, y1 - r),
        RemoteOffset(x1 - r, y1),
        0f to right,
        0.5f to lit(0.72f),
        1f to bottom,
      )
      // Lower left: from the bottom's light, through the turn, into the left side's shade.
      corner(
        x0,
        y1 - r2,
        90f,
        RemoteOffset(x0 + r, y1),
        RemoteOffset(x0, y1 - r),
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
 * A colour at a strength: a window layer's ink, which gradients fade by its alpha alone. A kit
 * colour is a constant ([fixed]), so its gradients are constant colour lists, as every player and
 * the JVM library's encoder agree on; a host's colour is its runtime role ([role]).
 */
private class Ink(val fixed: Color?, val role: RemoteColor?, val alpha: Float) {
  constructor(fixed: Color, alpha: Float) : this(fixed, null, alpha)

  constructor(role: RemoteColor, alpha: Float) : this(null, role, alpha)

  val rc: RemoteColor
    get() =
      fixed?.copy(alpha = fixed.alpha * alpha)?.rc ?: requireNotNull(role).copy(alpha = alpha.rf)

  fun at(alpha: Float) = Ink(fixed, role, alpha)
}

/**
 * One window's colours: the lit and shaded bevel, the wafer's walls, the lip's shadow and the
 * glass's sheen, each at full strength, and the shadow's gain.
 */
private class WindowLook(
  val lit: Ink,
  val shade: Ink,
  val wafer: Ink,
  val shadow: Ink,
  val shadowGain: Float,
  val sheen: Ink,
)

/**
 * The window's colours. Under the kit's own palette they're Greg's chamber colours: steel-blue
 * light and near-black shade on the dark (negative) display, white and graphite on the light one.
 * On a host's surfaces (a launcher widget in system colours) they're the host's runtime roles, so
 * the window's chrome follows the host's colours as its floor does: the wafer in the divider, the
 * lit edge in the text (dark) or card (light) colour, the shade and shadow neutral.
 */
@Composable
private fun windowLook(dark: Boolean): WindowLook {
  val theme = haTheme()
  val own = currentHaUberTheme().ownPalette
  val sheen = Ink(Color(175, 228, 255), 1f)
  return when {
    !own ->
      WindowLook(
        lit = Ink(if (dark) theme.primaryText else theme.cardBackground, 1f),
        shade = Ink(Color.Black, 1f),
        wafer = Ink(theme.divider, if (dark) 0.35f else 0.3f),
        shadow = Ink(Color.Black, 1f),
        shadowGain = if (dark) 1f else 0.4f,
        sheen = Ink(theme.primaryText, 1f),
      )
    dark ->
      WindowLook(
        lit = Ink(Color(150, 172, 198), 1f),
        shade = Ink(Color(3, 5, 8), 1f),
        wafer = Ink(Color(190, 215, 240), 0.05f),
        shadow = Ink(Color.Black, 1f),
        shadowGain = 1f,
        sheen = sheen,
      )
    else ->
      WindowLook(
        lit = Ink(Color.White, 1f),
        shade = Ink(Color(70, 76, 84), 1f),
        wafer = Ink(Color(70, 82, 96), 0.045f),
        shadow = Ink(Color(20, 26, 32), 1f),
        shadowGain = 0.5f,
        sheen = sheen,
      )
  }
}

/**
 * How many colours a window's gradient samples: smooth across the short runs they cover, and well
 * under the 255 every player takes, so each stays small.
 */
private const val MaxGradientColors = 16

/**
 * A fill paint holding a linear gradient from [from] to [to] through [stops] (position, ink),
 * sampled to [samples] evenly spaced colours: the one gradient form every player takes. Between two
 * stops the strength blends and the colour is the nearer stop's, so a gradient can pass from one
 * colour to another through nothing, and every colour keeps its runtime binding.
 */
private fun RemoteDrawScope.gradientPaint(
  from: RemoteOffset,
  to: RemoteOffset,
  stops: List<Pair<Float, Ink>>,
  samples: Int = MaxGradientColors,
): RemotePaint {
  val colors =
    List(samples) { i ->
      val t = i / (samples - 1f)
      val next = stops.indexOfFirst { it.first >= t }
      when {
        next < 0 -> stops.last().second.rc
        next == 0 -> stops.first().second.rc
        else -> {
          val (f0, c0) = stops[next - 1]
          val (f1, c1) = stops[next]
          val k = if (f1 > f0) (t - f0) / (f1 - f0) else 1f
          (if (k < 0.5f) c0 else c1).at(c0.alpha + (c1.alpha - c0.alpha) * k).rc
        }
      }
    }
  return RemotePaint().also { paint ->
    paint.style = PaintingStyle.Fill
    paint.isAntiAlias = true
    with(RemoteBrush.linearGradient(colors, from, to)) { applyTo(paint, size) }
  }
}
