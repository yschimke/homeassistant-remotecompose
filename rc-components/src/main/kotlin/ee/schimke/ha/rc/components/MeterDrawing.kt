@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import android.graphics.Paint as AndroidPaint
import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.asRemotePaint
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// The kit every visual family (Meters<Family>.kt) draws with: the colours a meter resolves from
// the active uber theme, the zones a reading marks, and canvas helpers. All geometry is RemoteFloat
// arithmetic on the canvas size, so every drawing fits and scales with its box at playback.
// ---------------------------------------------------------------------------------------------

/**
 * Where a reading's scale turns noteworthy, as fractions of the scale: [hotFrom] and up is "hot" (a
 * temperature past 30 °C, a gauge's red band), and below [lowBelow] is "low" (a battery past
 * critical). Families that segment their scale light those segments in the hot colour; others draw
 * a red zone. Null is none.
 */
data class HaMeterZones(val hotFrom: Float? = null, val lowBelow: Float? = null)

/** The colours one meter draws with, resolved from the active uber theme once per meter. */
internal class MeterLook(
  /** The value: the theme's ink, else the caller's (live) colour. */
  val value: RemoteColor,
  /** The scale's track: the caller's track, else the theme's divider. */
  val track: RemoteColor,
  /** Unlit segments: the ink at a ghost alpha, else the track. */
  val ghost: RemoteColor,
  /** Hot / low segments and zones: the hot ink, else the semantic critical red. */
  val hot: RemoteColor,
  /** Needles, hubs and scale ticks: the theme's primary text. */
  val text: RemoteColor,
  /** Secondary marks: the theme's secondary text. */
  val muted: RemoteColor,
  /** A pointer's accent: the theme's link colour (Braun's orange). */
  val accent: RemoteColor,
  /** The card surface, for hollowing shapes out. */
  val surface: RemoteColor,
  /** A soft halo under a glowing value, when the theme has an ink. */
  val glow: RemoteColor?,
  val inked: Boolean,
) {
  /**
   * The colour of segment [index] of [count] at [fraction]: unlit ([ghost]) until the value passes
   * the segment's midpoint — or, [fromStart], as soon as it enters the segment, the way a phone
   * keeps its last battery bar lit — and lit in [hot] inside a zone, else [value].
   */
  fun segment(
    fraction: RemoteFloat,
    index: Int,
    count: Int,
    zones: HaMeterZones,
    fromStart: Boolean = false,
  ): RemoteColor {
    val mid = (index + 0.5f) / count
    val litFrom = if (fromStart) index.toFloat() / count + 0.001f else mid
    val litColor =
      when {
        zones.hotFrom != null && mid >= zones.hotFrom -> hot
        zones.lowBelow != null ->
          fraction.isGreaterThanOrEqualTo(zones.lowBelow.rf).select(value, hot)
        else -> value
      }
    return fraction.isGreaterThanOrEqualTo(litFrom.rf).select(litColor, ghost)
  }
}

@Composable
internal fun meterLook(color: RemoteColor, trackColor: RemoteColor?): MeterLook {
  val active = currentHaUberTheme()
  val theme = haTheme()
  val ink = active.ink
  val track = trackColor ?: theme.divider
  return MeterLook(
    value = ink?.rc ?: color,
    track = track,
    ghost = ink?.copy(alpha = 0.12f)?.rc ?: track,
    hot = (active.hotInk ?: HaStatusColors.Critical).rc,
    text = theme.primaryText,
    muted = theme.secondaryText,
    accent = theme.linkText,
    surface = theme.cardBackground,
    glow = ink?.copy(alpha = 0.28f)?.rc,
    inked = ink != null,
  )
}

/** The active theme's visual family. */
@Composable internal fun visualFamily(): HaVisualFamily = currentHaUberTheme().theme.visuals

/** A box of aspect [aspectWOverH], the largest that fits [w] × [h], centred. */
internal class FitBox(w: RemoteFloat, h: RemoteFloat, aspectWOverH: Float) {
  val width: RemoteFloat = w.min(h * aspectWOverH.rf)
  val height: RemoteFloat = width / aspectWOverH.rf
  val left: RemoteFloat = (w - width) / 2f.rf
  val top: RemoteFloat = (h - height) / 2f.rf
  val right: RemoteFloat
    get() = left + width

  val bottom: RemoteFloat
    get() = top + height

  val centerX: RemoteFloat
    get() = left + width / 2f.rf
}

/** The half-circle an arc meter draws in: centre ([cx], [baseY]) and [radius], fitted. */
internal class ArcBox(w: RemoteFloat, h: RemoteFloat, pad: Float, baseline: Float) {
  val baseY: RemoteFloat = h * baseline.rf - pad.rf
  val radius: RemoteFloat = (baseY - pad.rf).min((w - (pad * 2f).rf) / 2f.rf)
  val cx: RemoteFloat = w / 2f.rf
  val topLeft: RemoteOffset = RemoteOffset(cx - radius, baseY - radius)
  val size: RemoteSize = RemoteSize(radius * 2f.rf, radius * 2f.rf)

  /** The point at [degrees] along the half circle (0 = left end, 180 = right end), at [r]. */
  fun point(degrees: Float, r: RemoteFloat): RemoteOffset =
    RemoteOffset(x(degrees, r), y(degrees, r))

  fun x(degrees: Float, r: RemoteFloat): RemoteFloat =
    cx + r * cos(Math.toRadians((180f + degrees).toDouble())).toFloat().rf

  fun y(degrees: Float, r: RemoteFloat): RemoteFloat =
    baseY + r * sin(Math.toRadians((180f + degrees).toDouble())).toFloat().rf
}

internal fun remoteMax(a: RemoteFloat, b: RemoteFloat): RemoteFloat = -((-a).min(-b))

internal fun RemoteDrawScope.fillCircle(
  paint: RemotePaint,
  cx: RemoteFloat,
  cy: RemoteFloat,
  radius: RemoteFloat,
) = drawCircle(paint, radius, RemoteOffset(cx, cy))

internal fun RemoteDrawScope.fillRect(
  color: RemoteColor,
  x: RemoteFloat,
  y: RemoteFloat,
  w: RemoteFloat,
  h: RemoteFloat,
) = drawRect(fillPaint(color), RemoteOffset(x, y), RemoteSize(w, h))

/** A rounded rect with corner [radius]. */
internal fun RemoteDrawScope.fillRound(
  color: RemoteColor,
  x: RemoteFloat,
  y: RemoteFloat,
  w: RemoteFloat,
  h: RemoteFloat,
  radius: RemoteFloat,
) =
  drawRoundRect(
    fillPaint(color),
    RemoteOffset(x, y),
    RemoteSize(w, h),
    RemoteOffset(radius, radius),
  )

/** A bar with fully round ends on its cross axis. */
internal fun RemoteDrawScope.roundedBar(
  paint: RemotePaint,
  x: RemoteFloat,
  y: RemoteFloat,
  w: RemoteFloat,
  h: RemoteFloat,
  vertical: Boolean,
) {
  val radius = (if (vertical) w else h) / 2f.rf
  drawRoundRect(paint, RemoteOffset(x, y), RemoteSize(w, h), RemoteOffset(radius, radius))
}

/**
 * A rectangle outline [stroke] thick, drawn as four filled bars (so the line scales with the box,
 * unlike a stroke paint's fixed width).
 */
internal fun RemoteDrawScope.outlineRect(
  color: RemoteColor,
  x: RemoteFloat,
  y: RemoteFloat,
  w: RemoteFloat,
  h: RemoteFloat,
  stroke: RemoteFloat,
) {
  fillRect(color, x, y, w, stroke)
  fillRect(color, x, y + h - stroke, w, stroke)
  fillRect(color, x, y, stroke, h)
  fillRect(color, x + w - stroke, y, stroke, h)
}

internal fun strokePaint(widthPx: Float, color: RemoteColor, round: Boolean = true) =
  AndroidPaint()
    .apply {
      isAntiAlias = true
      style = AndroidPaint.Style.STROKE
      strokeWidth = widthPx
      strokeCap = if (round) AndroidPaint.Cap.ROUND else AndroidPaint.Cap.BUTT
    }
    .asRemotePaint()
    .apply { this.color = color }

internal fun fillPaint(color: RemoteColor) =
  AndroidPaint()
    .apply {
      isAntiAlias = true
      style = AndroidPaint.Style.FILL
    }
    .asRemotePaint()
    .apply { this.color = color }
