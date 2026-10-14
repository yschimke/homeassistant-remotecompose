@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import android.graphics.Paint as AndroidPaint
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.asRemotePaint
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable

// ---------------------------------------------------------------------------------------------
// Layer 2 of the card data hierarchy: visual meters. Each one draws a normalised `0..1` fraction
// in a colour it is handed, and nothing else — no text, no chrome, no knowledge of entities — so
// the same meter serves a gauge card, a thermometer, a launcher chip or a Wear widget.
//
// Rules every meter follows (card-component-system.md §"Visual meters"):
//
// - **It fits the box it is given**, keeping its own aspect: the drawing is computed from the
//   canvas `width` / `height` at playback, so a meter grows and shrinks with its cell with no
//   re-encode. Size it from outside with the modifier.
// - **The track is the theme's `divider`**, the value is the caller's colour (a [HaMeterColor]
//   expression, so it can change with the live value).
// - **Fills, not strokes**, where the shape allows: a filled shape scales with the canvas, a
//   stroke's width is fixed at capture.
// - **It draws the active uber theme's form** ([HaMeterStyle]): smooth or segmented, its stroke
//   scale, M3 Expressive's track gap and stop dot, and a glow; and in the theme's ink, when it has
//   one. A segment is lit by a select on the live fraction, so a segmented meter stays live too.
// ---------------------------------------------------------------------------------------------

/** What every meter needs from the active uber theme, resolved once per meter. */
private class MeterLook(
  val style: HaMeterStyle,
  val value: RemoteColor,
  val track: RemoteColor,
  /** Unlit segments: the ink's ghost, or the track. */
  val unlit: RemoteColor,
  /** The halo under a glowing value, or null. */
  val glow: RemoteColor?,
) {
  /** [value] when segment [index] of [count] is lit at [fraction], else [unlit]. */
  fun segment(fraction: RemoteFloat, index: Int, count: Int): RemoteColor =
    fraction.isGreaterThanOrEqualTo(((index + 0.5f) / count).rf).select(value, unlit)
}

@Composable
private fun meterLook(color: RemoteColor, trackColor: RemoteColor?): MeterLook {
  val active = currentHaUberTheme()
  val style = active.theme.meter
  val ink = active.ink
  val track = trackColor ?: haTheme().divider
  val ghost = (style.form as? HaMeterForm.Segmented)?.ghostAlpha
  return MeterLook(
    style = style,
    value = ink?.rc ?: color,
    track = track,
    unlit = if (ink != null && ghost != null) ink.copy(alpha = ghost).rc else track,
    glow = if (style.glow) (ink ?: HaStatusColors.None).copy(alpha = 0.28f).rc else null,
  )
}

/**
 * A half-circle arc meter, its base at [baseline] of the canvas height (1 = the bottom edge) and
 * its diameter the largest that fits. The value arc sweeps `fraction × 180°` from the left.
 *
 * @param strokeWidthPx the arc's stroke, in document pixels (fixed at capture), before the uber
 *   theme's stroke scale.
 */
@Composable
@RemoteComposable
fun RemoteHaArcMeter(
  fraction: RemoteFloat,
  color: RemoteColor,
  modifier: RemoteModifier = RemoteModifier,
  strokeWidthPx: Float = 8f,
  baseline: Float = 1f,
  trackColor: RemoteColor? = null,
) {
  val look = meterLook(color, trackColor)
  val stroke = strokeWidthPx * look.style.strokeScale
  RemoteCanvas(modifier = modifier) {
    val w = width
    val h = height
    val pad = ((if (look.glow != null) stroke * 1.5f else stroke / 2f) + 1f).rf
    val baseY = h * baseline.rf - pad
    val byHeight = (baseY - pad) * 2f.rf
    val byWidth = w - pad * 2f.rf
    val diameter = byHeight.min(byWidth)
    val arcLeft = (w - diameter) / 2f.rf
    val topLeft = RemoteOffset(arcLeft, baseY - diameter / 2f.rf)
    val arcSize = RemoteSize(diameter, diameter)
    val sweep = fraction * 180f.rf
    when (val form = look.style.form) {
      is HaMeterForm.Segmented -> {
        // Each segment a short butt-capped arc: thin ones read as a tick scale, wide ones as LCD
        // blocks.
        val pitch = 180f / form.count
        for (i in 0 until form.count) {
          drawArc(
            strokePaint(stroke, look.segment(fraction, i, form.count), round = false),
            (180f + i * pitch + pitch * form.gap / 2f).rf,
            (pitch * (1f - form.gap)).rf,
            false,
            topLeft,
            arcSize,
          )
        }
      }
      HaMeterForm.Smooth -> {
        if (look.style.trackGap) {
          // M3 Expressive: the track starts a gap after the value and ends in a stop dot.
          val gapDeg = 9f.rf
          val trackStart = 180f.rf + sweep + gapDeg
          val trackSweep = maxOf(180f.rf - sweep - gapDeg, 0f.rf)
          drawArc(strokePaint(stroke, look.track), trackStart, trackSweep, false, topLeft, arcSize)
          fillCircle(fillPaint(look.value), arcLeft + diameter, baseY, (stroke * 0.22f).rf)
        } else {
          drawArc(strokePaint(stroke, look.track), 180f.rf, 180f.rf, false, topLeft, arcSize)
        }
        look.glow?.let {
          drawArc(strokePaint(stroke * 3f, it), 180f.rf, sweep, false, topLeft, arcSize)
        }
        drawArc(strokePaint(stroke, look.value), 180f.rf, sweep, false, topLeft, arcSize)
      }
    }
  }
}

/**
 * A straight bar meter: a track the full length of the canvas and a value bar `fraction` of the way
 * along it, horizontal (filling left to right) or [vertical] (bottom to top). The bar is the
 * canvas's full cross-axis thickness, so set that with the modifier (`height(8.rdp)`).
 */
@Composable
@RemoteComposable
fun RemoteHaBarMeter(
  fraction: RemoteFloat,
  color: RemoteColor,
  modifier: RemoteModifier = RemoteModifier,
  vertical: Boolean = false,
  trackColor: RemoteColor? = null,
) {
  val look = meterLook(color, trackColor)
  RemoteCanvas(modifier = modifier) {
    val w = width
    val h = height
    // Work along the bar's length (`len`) and across it (`thick`), then map to x/y.
    val len = if (vertical) h else w
    val thick = if (vertical) w else h
    fun bar(paint: RemotePaint, from: RemoteFloat, size: RemoteFloat, rounded: Boolean = true) {
      if (vertical) {
        val top = h - from - size
        if (rounded) roundedBar(paint, 0f.rf, top, w, size, vertical = true)
        else drawRect(paint, RemoteOffset(0f.rf, top), RemoteSize(w, size))
      } else {
        if (rounded) roundedBar(paint, from, 0f.rf, size, h, vertical = false)
        else drawRect(paint, RemoteOffset(from, 0f.rf), RemoteSize(size, h))
      }
    }
    when (val form = look.style.form) {
      is HaMeterForm.Segmented -> {
        val pitch = len / form.count.toFloat().rf
        val block = pitch * (1f - form.gap).rf
        for (i in 0 until form.count) {
          bar(
            fillPaint(look.segment(fraction, i, form.count)),
            pitch * i.toFloat().rf,
            block,
            false,
          )
        }
      }
      HaMeterForm.Smooth -> {
        val filled = len * fraction
        if (look.style.trackGap) {
          val gap = thick
          val trackFrom = filled + gap
          bar(fillPaint(look.track), trackFrom, maxOf(len - trackFrom, 0f.rf))
          val dotR = thick * 0.2f.rf
          val dotAlong = len - thick / 2f.rf
          if (vertical) fillCircle(fillPaint(look.value), w / 2f.rf, h - dotAlong, dotR)
          else fillCircle(fillPaint(look.value), dotAlong, h / 2f.rf, dotR)
        } else {
          bar(fillPaint(look.track), 0f.rf, len)
        }
        bar(fillPaint(look.value), 0f.rf, filled)
      }
    }
  }
}

/**
 * A liquid thermometer: a round-topped tube over a bulb, filled from the bulb up to `fraction` of
 * the tube. Fits the canvas at a fixed 1 : 2.6 aspect, centred, so it reads as a thermometer from a
 * 16dp chip glyph to a full-height widget. Segmented themes stack the column as blocks.
 */
@Composable
@RemoteComposable
fun RemoteHaThermometerGlyph(
  fraction: RemoteFloat,
  color: RemoteColor,
  modifier: RemoteModifier = RemoteModifier,
  trackColor: RemoteColor? = null,
) {
  val look = meterLook(color, trackColor)
  val liquidShare = (0.5f * look.style.strokeScale).coerceIn(0.3f, 0.8f)
  RemoteCanvas(modifier = modifier) {
    val w = width
    val h = height
    // Glyph box at aspect 1 : ThermometerAspect, as large as fits.
    val glyphH = h.min(w * ThermometerAspect.rf)
    val glyphW = glyphH / ThermometerAspect.rf
    val left = (w - glyphW) / 2f.rf
    val top = (h - glyphH) / 2f.rf
    val cx = left + glyphW / 2f.rf
    val bulbR = glyphW / 2f.rf
    val bulbCy = top + glyphH - bulbR
    val tubeW = glyphW * 0.5f.rf
    val tubeTop = top + tubeW / 2f.rf
    val trackPaint = fillPaint(look.track)

    // Track: tube (rect + round cap) and bulb.
    drawRect(
      trackPaint,
      RemoteOffset(cx - tubeW / 2f.rf, tubeTop),
      RemoteSize(tubeW, bulbCy - tubeTop),
    )
    fillCircle(trackPaint, cx, tubeTop, tubeW / 2f.rf)
    fillCircle(trackPaint, cx, bulbCy, bulbR)

    val liquidW = tubeW * liquidShare.rf
    val column = bulbCy - tubeTop
    when (val form = look.style.form) {
      is HaMeterForm.Segmented -> {
        // Blocks from the bulb up, a thermometer's scale as an LCD would draw it.
        val count = form.count.coerceAtMost(MaxGlyphSegments)
        val pitch = (column - bulbR) / count.toFloat().rf
        val block = pitch * (1f - form.gap).rf
        for (i in 0 until count) {
          val blockTop = bulbCy - bulbR - pitch * (i + 1).toFloat().rf
          drawRect(
            fillPaint(look.segment(fraction, i, count)),
            RemoteOffset(cx - liquidW / 2f.rf, blockTop + (pitch - block)),
            RemoteSize(liquidW, block),
          )
        }
      }
      HaMeterForm.Smooth -> {
        val levelTop = tubeTop + column * (1f.rf - fraction)
        look.glow?.let {
          val halo = fillPaint(it)
          val haloW = liquidW * 2.4f.rf
          drawRect(
            halo,
            RemoteOffset(cx - haloW / 2f.rf, levelTop),
            RemoteSize(haloW, bulbCy - levelTop),
          )
          fillCircle(halo, cx, levelTop, haloW / 2f.rf)
        }
        val valuePaint = fillPaint(look.value)
        drawRect(
          valuePaint,
          RemoteOffset(cx - liquidW / 2f.rf, levelTop),
          RemoteSize(liquidW, bulbCy - levelTop),
        )
        fillCircle(valuePaint, cx, levelTop, liquidW / 2f.rf)
      }
    }
    // The bulb is always full: it's the thermometer's identity at any reading.
    look.glow?.let { fillCircle(fillPaint(it), cx, bulbCy, bulbR) }
    fillCircle(fillPaint(look.value), cx, bulbCy, bulbR * (0.55f + 0.15f * liquidShare / 0.5f).rf)
  }
}

/**
 * A battery: a body with a terminal nub on the right, its charge a bar `fraction` of the body's
 * inner width. Fits the canvas at a fixed 2 : 1 aspect, centred. Segmented themes draw the charge
 * as cells.
 */
@Composable
@RemoteComposable
fun RemoteHaBatteryGlyph(
  fraction: RemoteFloat,
  color: RemoteColor,
  modifier: RemoteModifier = RemoteModifier,
  trackColor: RemoteColor? = null,
) {
  val look = meterLook(color, trackColor)
  val surface = haTheme().cardBackground
  val wallShare = (0.12f * look.style.strokeScale).coerceIn(0.06f, 0.2f)
  RemoteCanvas(modifier = modifier) {
    val w = width
    val h = height
    val glyphW = w.min(h * BatteryAspect.rf)
    val glyphH = glyphW / BatteryAspect.rf
    val left = (w - glyphW) / 2f.rf
    val top = (h - glyphH) / 2f.rf
    val nubW = glyphW * 0.07f.rf
    val bodyW = glyphW - nubW
    val wall = glyphH * wallShare.rf
    val trackPaint = fillPaint(look.track)

    // Body outline: the track colour, hollowed out with the card surface.
    val corner = glyphH * 0.18f.rf
    drawRoundRect(
      trackPaint,
      RemoteOffset(left, top),
      RemoteSize(bodyW, glyphH),
      RemoteOffset(corner, corner),
    )
    val innerCorner = corner - wall
    drawRoundRect(
      fillPaint(surface),
      RemoteOffset(left + wall, top + wall),
      RemoteSize(bodyW - wall * 2f.rf, glyphH - wall * 2f.rf),
      RemoteOffset(innerCorner, innerCorner),
    )
    drawRect(
      trackPaint,
      RemoteOffset(left + bodyW, top + glyphH * 0.3f.rf),
      RemoteSize(nubW, glyphH * 0.4f.rf),
    )
    // Charge, inset one wall inside the hollow.
    val inset = wall * 2f.rf
    val innerW = bodyW - inset * 2f.rf
    val innerH = glyphH - inset * 2f.rf
    when (val form = look.style.form) {
      is HaMeterForm.Segmented -> {
        // A fine scale keeps up to a dozen cells; a coarse one is a phone's five bars.
        val count = if (form.count > MaxGlyphSegments) MaxGlyphSegments else 5
        val pitch = innerW / count.toFloat().rf
        val cell = pitch * (1f - form.gap.coerceAtLeast(0.15f)).rf
        for (i in 0 until count) {
          drawRect(
            fillPaint(look.segment(fraction, i, count)),
            RemoteOffset(left + inset + pitch * i.toFloat().rf, top + inset),
            RemoteSize(cell, innerH),
          )
        }
      }
      HaMeterForm.Smooth -> {
        look.glow?.let {
          drawRect(
            fillPaint(it),
            RemoteOffset(left + wall, top + wall),
            RemoteSize((innerW + inset) * fraction, glyphH - wall * 2f.rf),
          )
        }
        drawRect(
          fillPaint(look.value),
          RemoteOffset(left + inset, top + inset),
          RemoteSize(innerW * fraction, innerH),
        )
      }
    }
  }
}

/** Height : width of [RemoteHaThermometerGlyph]. */
const val ThermometerAspect = 2.6f

/** Width : height of [RemoteHaBatteryGlyph]. */
const val BatteryAspect = 2f

/** A glyph draws at most this many segments, whatever the theme's scale asks for. */
private const val MaxGlyphSegments = 12

private fun maxOf(a: RemoteFloat, b: RemoteFloat): RemoteFloat = -((-a).min(-b))

private fun RemoteDrawScope.fillCircle(
  paint: RemotePaint,
  cx: RemoteFloat,
  cy: RemoteFloat,
  radius: RemoteFloat,
) = drawCircle(paint, radius, RemoteOffset(cx, cy))

/** A bar with fully round ends on its cross axis. */
private fun RemoteDrawScope.roundedBar(
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

private fun strokePaint(widthPx: Float, color: RemoteColor, round: Boolean = true) =
  AndroidPaint()
    .apply {
      isAntiAlias = true
      style = AndroidPaint.Style.STROKE
      strokeWidth = widthPx
      strokeCap = if (round) AndroidPaint.Cap.ROUND else AndroidPaint.Cap.BUTT
    }
    .asRemotePaint()
    .apply { this.color = color }

private fun fillPaint(color: RemoteColor) =
  AndroidPaint()
    .apply {
      isAntiAlias = true
      style = AndroidPaint.Style.FILL
    }
    .asRemotePaint()
    .apply { this.color = color }
