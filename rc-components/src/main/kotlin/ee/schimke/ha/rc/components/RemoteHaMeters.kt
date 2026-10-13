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
// ---------------------------------------------------------------------------------------------

/**
 * A half-circle arc meter, its base at [baseline] of the canvas height (1 = the bottom edge) and
 * its diameter the largest that fits. The value arc sweeps `fraction × 180°` from the left.
 *
 * @param strokeWidthPx the arc's stroke, in document pixels (fixed at capture).
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
  val track = trackColor ?: haTheme().divider
  RemoteCanvas(modifier = modifier) {
    val w = width
    val h = height
    val pad = (strokeWidthPx / 2f + 1f).rf
    val baseY = h * baseline.rf - pad
    val byHeight = (baseY - pad) * 2f.rf
    val byWidth = w - pad * 2f.rf
    val diameter = byHeight.min(byWidth)
    val topLeft = RemoteOffset((w - diameter) / 2f.rf, baseY - diameter / 2f.rf)
    val arcSize = RemoteSize(diameter, diameter)
    drawArc(strokePaint(strokeWidthPx, track), 180f.rf, 180f.rf, false, topLeft, arcSize)
    drawArc(strokePaint(strokeWidthPx, color), 180f.rf, fraction * 180f.rf, false, topLeft, arcSize)
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
  val track = trackColor ?: haTheme().divider
  RemoteCanvas(modifier = modifier) {
    val w = width
    val h = height
    val trackPaint = fillPaint(track)
    val valuePaint = fillPaint(color)
    if (vertical) {
      roundedBar(trackPaint, 0f.rf, 0f.rf, w, h, vertical = true)
      val filled = h * fraction
      roundedBar(valuePaint, 0f.rf, h - filled, w, filled, vertical = true)
    } else {
      roundedBar(trackPaint, 0f.rf, 0f.rf, w, h, vertical = false)
      roundedBar(valuePaint, 0f.rf, 0f.rf, w * fraction, h, vertical = false)
    }
  }
}

/**
 * A liquid thermometer: a round-topped tube over a bulb, filled from the bulb up to `fraction` of
 * the tube. Fits the canvas at a fixed 1 : 2.6 aspect, centred, so it reads as a thermometer from a
 * 16dp chip glyph to a full-height widget.
 */
@Composable
@RemoteComposable
fun RemoteHaThermometerGlyph(
  fraction: RemoteFloat,
  color: RemoteColor,
  modifier: RemoteModifier = RemoteModifier,
  trackColor: RemoteColor? = null,
) {
  val track = trackColor ?: haTheme().divider
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
    val trackPaint = fillPaint(track)
    val valuePaint = fillPaint(color)

    // Track: tube (rect + round cap) and bulb.
    drawRect(
      trackPaint,
      RemoteOffset(cx - tubeW / 2f.rf, tubeTop),
      RemoteSize(tubeW, bulbCy - tubeTop),
    )
    fillCircle(trackPaint, cx, tubeTop, tubeW / 2f.rf)
    fillCircle(trackPaint, cx, bulbCy, bulbR)

    // Liquid: an inset column from the bulb to the level, and the inset bulb.
    val liquidW = tubeW * 0.5f.rf
    val levelTop = tubeTop + (bulbCy - tubeTop) * (1f.rf - fraction)
    drawRect(
      valuePaint,
      RemoteOffset(cx - liquidW / 2f.rf, levelTop),
      RemoteSize(liquidW, bulbCy - levelTop),
    )
    fillCircle(valuePaint, cx, levelTop, liquidW / 2f.rf)
    fillCircle(valuePaint, cx, bulbCy, bulbR * 0.7f.rf)
  }
}

/**
 * A battery: a body with a terminal nub on the right, its charge a bar `fraction` of the body's
 * inner width. Fits the canvas at a fixed 2 : 1 aspect, centred.
 */
@Composable
@RemoteComposable
fun RemoteHaBatteryGlyph(
  fraction: RemoteFloat,
  color: RemoteColor,
  modifier: RemoteModifier = RemoteModifier,
  trackColor: RemoteColor? = null,
) {
  val track = trackColor ?: haTheme().divider
  val surface = haTheme().cardBackground
  RemoteCanvas(modifier = modifier) {
    val w = width
    val h = height
    val glyphW = w.min(h * BatteryAspect.rf)
    val glyphH = glyphW / BatteryAspect.rf
    val left = (w - glyphW) / 2f.rf
    val top = (h - glyphH) / 2f.rf
    val nubW = glyphW * 0.07f.rf
    val bodyW = glyphW - nubW
    val wall = glyphH * 0.12f.rf
    val trackPaint = fillPaint(track)

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
    drawRect(
      fillPaint(color),
      RemoteOffset(left + inset, top + inset),
      RemoteSize(innerW * fraction, glyphH - inset * 2f.rf),
    )
  }
}

/** Height : width of [RemoteHaThermometerGlyph]. */
const val ThermometerAspect = 2.6f

/** Width : height of [RemoteHaBatteryGlyph]. */
const val BatteryAspect = 2f

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

private fun strokePaint(widthPx: Float, color: RemoteColor) =
  AndroidPaint()
    .apply {
      isAntiAlias = true
      style = AndroidPaint.Style.STROKE
      strokeWidth = widthPx
      strokeCap = AndroidPaint.Cap.ROUND
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
