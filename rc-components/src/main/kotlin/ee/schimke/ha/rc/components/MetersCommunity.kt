@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.ui.graphics.Color

// These are original vector grammars, not upstream artwork. All filled geometry, cells and
// pointers use the incoming RemoteFloat. The same grammar serves half gauges and control rings.
internal fun RemoteDrawScope.communityArc(
  family: HaVisualFamily,
  look: MeterLook,
  fraction: RemoteFloat,
  stroke: Float,
  baseline: Float,
  zones: HaMeterZones,
) {
  val effectiveStroke = stroke * 0.65f
  val box = ArcBox(width, height, effectiveStroke + 1f, baseline)
  communityRing(
    family,
    look,
    fraction,
    box.cx,
    box.baseY,
    box.radius,
    180f,
    180f,
    effectiveStroke,
    zones,
  )
}

internal fun RemoteDrawScope.communityRing(
  family: HaVisualFamily,
  look: MeterLook,
  value: RemoteFloat,
  cx: RemoteFloat,
  cy: RemoteFloat,
  radius: RemoteFloat,
  start: Float,
  sweep: Float,
  stroke: Float,
  zones: HaMeterZones = HaMeterZones(),
) {
  fun arc(
    inset: Float,
    thickness: Float,
    color: androidx.compose.remote.creation.compose.state.RemoteColor,
    from: RemoteFloat = start.rf,
    length: RemoteFloat = sweep.rf,
    round: Boolean = true,
  ) {
    val r = remoteMax(radius - inset.rf, radius * 0.15f.rf)
    drawArc(
      strokePaint(thickness, color, round),
      from,
      length,
      false,
      RemoteOffset(cx - r, cy - r),
      RemoteSize(r * 2f.rf, r * 2f.rf),
    )
  }
  fun cells(count: Int, dot: Boolean, share: Float, thick: Float) {
    for (i in 0 until count) {
      val color = look.segment(value, i, count, zones)
      val deg = start + (i + 0.5f) * sweep / count
      if (dot) drawCircle(fillPaint(color), (thick / 2f).rf, polar(cx, cy, deg, radius))
      else
        arc(
          0f,
          thick,
          color,
          (start + (i + (1f - share) / 2f) * sweep / count).rf,
          (sweep / count * share).rf,
          round = false,
        )
    }
  }
  fun index(length: Float, thickness: Float, diamond: Boolean = false) {
    rotate(value * sweep.rf, RemoteOffset(cx, cy)) {
      val p = polar(cx, cy, start, radius)
      if (diamond) {
        rotate((start + 45f).rf, p) {
          fillRect(
            look.value,
            p.x - (thickness / 2f).rf,
            p.y - (thickness / 2f).rf,
            thickness.rf,
            thickness.rf,
          )
        }
      } else
        drawLine(
          strokePaint(thickness, look.value, false),
          polar(cx, cy, start, radius + (length / 2f).rf),
          polar(cx, cy, start, radius - length.rf),
        )
    }
  }
  when (family) {
    HaVisualFamily.Mushroom -> {
      // Petals around a quiet stem: a smooth inner scale plus a ring of seed heads.
      arc(stroke, 2f, look.track)
      for (i in 0..12) {
        val deg = start + (i + 0.5f) * sweep / 13f
        drawLine(
          strokePaint(1f, look.track),
          polar(cx, cy, deg, radius - stroke.rf),
          polar(cx, cy, deg, radius),
        )
      }
      cells(13, true, 1f, stroke * 1.1f)
    }
    HaVisualFamily.Bubble -> {
      // Large separated round beads; a continuous thin hoop keeps low values readable.
      arc(0f, 2f, look.track)
      cells(9, true, 1f, stroke * 1.8f)
    }
    HaVisualFamily.Minimalist -> {
      arc(0f, stroke * 0.45f, look.track)
      arc(0f, stroke * 0.45f, look.value, length = value * sweep.rf)
      for (i in 0..4) drawCircle(
        fillPaint(look.muted),
        2f.rf,
        polar(cx, cy, start + i * sweep / 4f, radius - stroke.rf),
      )
      index(stroke, stroke * 0.7f)
    }
    HaVisualFamily.Cupertino -> {
      // Two concentric rings, with an inset full activity track.
      arc(stroke * 1.2f, stroke * 0.45f, look.track)
      arc(0f, stroke, look.track)
      arc(0f, stroke, look.value, length = value * sweep.rf)
      arc(stroke * 1.2f, stroke * 0.45f, look.accent, length = value * sweep.rf)
    }
    HaVisualFamily.Catppuccin -> {
      cells(8, false, 0.72f, stroke * 1.25f)
      // A stitched inner edge, kept separate from the semantic value cells.
      for (i in 0..16) drawCircle(
        fillPaint(look.accent),
        1f.rf,
        polar(cx, cy, start + i * sweep / 16f, radius - (stroke * 1.2f).rf),
      )
    }
    HaVisualFamily.Obsidian -> {
      arc(0f, 1f, look.muted, round = false)
      for (i in 0..10) drawLine(
        strokePaint(1f, look.muted, false),
        polar(cx, cy, start + i * sweep / 10f, radius),
        polar(
          cx,
          cy,
          start + i * sweep / 10f,
          radius - (if (i % 5 == 0) stroke else stroke / 2f).rf,
        ),
      )
      index(stroke * 1.5f, 2f)
    }
    HaVisualFamily.Noctis -> {
      arc(0f, 2f, look.track)
      arc(stroke, 2f, look.track)
      arc(stroke / 2f, stroke * 0.55f, look.value, length = value * sweep.rf)
      rotate(value * sweep.rf, RemoteOffset(cx, cy)) {
        drawCircle(
          strokePaint(2f, look.value),
          (stroke * 0.55f).rf,
          polar(cx, cy, start, radius - (stroke / 2f).rf),
        )
      }
    }
    HaVisualFamily.Lcars -> {
      // Broad segmented elbow bands with a cutout inner rail.
      cells(6, false, 0.91f, stroke * 1.8f)
      arc(
        stroke * 1.7f,
        3f,
        look.accent,
        from = (start + sweep * 0.16f).rf,
        length = (sweep * 0.68f).rf,
        round = false,
      )
    }
    HaVisualFamily.Arctic -> {
      arc(0f, 1.5f, look.track)
      if (sweep > 180f) {
        // A control ring has text in its centre: keep the horizon and index outside that window.
        val horizon = cy + radius * 0.9f.rf
        drawLine(
          strokePaint(1.5f, look.muted),
          RemoteOffset(cx - radius * 0.6f.rf, horizon),
          RemoteOffset(cx + radius * 0.6f.rf, horizon),
        )
        rotate(value * sweep.rf, RemoteOffset(cx, cy)) {
          drawLine(
            strokePaint(2f, look.value),
            polar(cx, cy, start, radius * 0.74f.rf),
            polar(cx, cy, start, radius),
          )
        }
      } else {
        // A half gauge has its reading below the scale, leaving room for a suspended needle.
        drawLine(
          strokePaint(1.5f, look.muted),
          RemoteOffset(cx - radius, cy),
          RemoteOffset(cx + radius, cy),
        )
        rotate(value * sweep.rf, RemoteOffset(cx, cy)) {
          drawLine(
            strokePaint(2f, look.value),
            RemoteOffset(cx, cy),
            polar(cx, cy, start, radius - (stroke / 2f).rf),
          )
        }
        drawCircle(fillPaint(look.value), 3f.rf, RemoteOffset(cx, cy))
      }
    }
    HaVisualFamily.Aeon -> {
      arc(0f, 1f, look.accent, round = false)
      arc(stroke, 1f, look.accent, round = false)
      arc(stroke / 2f, stroke * 0.55f, look.value, length = value * sweep.rf, round = false)
      index(stroke, stroke * 1.2f, diamond = true)
    }
    else -> error("Not a community family: $family")
  }
}

/** Straight scales have their own grammars as well; the geometry is normalized before drawing. */
internal fun RemoteDrawScope.communityBar(
  family: HaVisualFamily,
  look: MeterLook,
  value: RemoteFloat,
  vertical: Boolean,
  zones: HaMeterZones = HaMeterZones(),
  canvasWidth: RemoteFloat = width,
  canvasHeight: RemoteFloat = height,
  originX: RemoteFloat = 0f.rf,
  originY: RemoteFloat = 0f.rf,
) {
  val len = if (vertical) canvasHeight else canvasWidth
  val thick = if (vertical) canvasWidth else canvasHeight
  fun rect(
    color: androidx.compose.remote.creation.compose.state.RemoteColor,
    from: RemoteFloat,
    length: RemoteFloat,
    offset: RemoteFloat,
    breadth: RemoteFloat,
    round: Boolean = false,
  ) {
    val x = if (vertical) offset else from
    val y = if (vertical) len - from - length else offset
    // Keep empty fills geometrically non-degenerate at playback. A transparent subpixel box
    // expresses an empty sample without passing a zero-size shape through the player.
    val safeLength = remoteMax(length, 0.01f.rf)
    val ink = length.isGreaterThanOrEqualTo(0.0001f.rf).select(color, Color.Transparent.rc)
    val w = if (vertical) breadth else safeLength
    val h = if (vertical) safeLength else breadth
    if (round) fillRound(ink, originX + x, originY + y, w, h, breadth / 2f.rf)
    else fillRect(ink, originX + x, originY + y, w, h)
  }
  fun cells(count: Int, share: Float, round: Boolean) {
    val step = len / count.toFloat().rf
    for (i in 0 until count) rect(
      look.segment(value, i, count, zones),
      step * (i + (1f - share) / 2f).rf,
      step * share.rf,
      thick * 0.1f.rf,
      thick * 0.8f.rf,
      round,
    )
  }
  when (family) {
    HaVisualFamily.Mushroom -> {
      rect(look.track, 0f.rf, len, thick * 0.4f.rf, thick * 0.2f.rf, true)
      cells(7, 0.7f, true)
    }
    HaVisualFamily.Bubble -> cells(5, 0.78f, true)
    HaVisualFamily.Minimalist -> {
      rect(look.track, 0f.rf, len, thick * 0.4f.rf, thick * 0.2f.rf)
      rect(look.value, 0f.rf, len * value, thick * 0.4f.rf, thick * 0.2f.rf)
      rect(look.value, (len - thick * 0.4f.rf) * value, thick * 0.4f.rf, 0f.rf, thick)
    }
    HaVisualFamily.Cupertino -> {
      rect(look.track, 0f.rf, len, 0f.rf, thick, true)
      rect(look.value, 0f.rf, len * value, 0f.rf, thick, true)
      rect(look.surface, len * 0.08f.rf, len * 0.1f.rf, thick * 0.4f.rf, thick * 0.2f.rf, true)
    }
    HaVisualFamily.Catppuccin -> {
      cells(8, 0.72f, true)
      rect(look.accent, 0f.rf, len, thick * 0.94f.rf, thick * 0.06f.rf)
    }
    HaVisualFamily.Obsidian -> {
      rect(look.muted, 0f.rf, len, thick * 0.75f.rf, thick * 0.08f.rf)
      for (i in 0..10) rect(
        look.muted,
        (len - thick * 0.08f.rf) * (i / 10f).rf,
        thick * 0.08f.rf,
        thick * 0.45f.rf,
        thick * 0.3f.rf,
      )
      rect(look.value, (len - thick * 0.2f.rf) * value, thick * 0.2f.rf, 0f.rf, thick)
    }
    HaVisualFamily.Noctis -> {
      rect(look.track, 0f.rf, len, thick * 0.1f.rf, thick * 0.12f.rf)
      rect(look.track, 0f.rf, len, thick * 0.78f.rf, thick * 0.12f.rf)
      rect(look.value, 0f.rf, len * value, thick * 0.4f.rf, thick * 0.2f.rf, true)
    }
    HaVisualFamily.Lcars -> {
      cells(4, 0.9f, false)
      rect(look.accent, 0f.rf, len * 0.15f.rf, 0f.rf, thick, true)
    }
    HaVisualFamily.Arctic -> {
      rect(look.muted, 0f.rf, len, thick * 0.5f.rf, thick * 0.08f.rf)
      rect(look.value, (len - thick * 0.15f.rf) * value, thick * 0.15f.rf, 0f.rf, thick)
      rect(look.track, 0f.rf, len, thick * 0.9f.rf, thick * 0.05f.rf)
    }
    HaVisualFamily.Aeon -> {
      rect(look.accent, 0f.rf, len, 0f.rf, thick * 0.08f.rf)
      rect(look.accent, 0f.rf, len, thick * 0.92f.rf, thick * 0.08f.rf)
      rect(look.value, 0f.rf, len * value, thick * 0.25f.rf, thick * 0.5f.rf)
      rect(look.accent, 0f.rf, thick * 0.08f.rf, 0f.rf, thick)
      rect(look.accent, len - thick * 0.08f.rf, thick * 0.08f.rf, 0f.rf, thick)
    }
    else -> error("Not a community family: $family")
  }
}

internal fun RemoteDrawScope.communityThermometer(
  family: HaVisualFamily,
  look: MeterLook,
  value: RemoteFloat,
  zones: HaMeterZones,
) {
  // Each scale's distinctive cross section stays readable in both chip and expanded form.
  val box = FitBox(width, height, 0.34f)
  val pad = box.width * 0.14f.rf
  val w = box.width - pad * 2f.rf
  val h = box.height - pad * 2f.rf
  communityBar(family, look, value, true, zones, w, h, box.left + pad, box.top + pad)
}

internal fun RemoteDrawScope.communityBattery(
  family: HaVisualFamily,
  look: MeterLook,
  value: RemoteFloat,
  zones: HaMeterZones,
) {
  val box = FitBox(width, height, 2.4f)
  val terminal = box.width * 0.055f.rf
  fillRound(
    look.muted,
    box.right - terminal,
    box.top + box.height * 0.33f.rf,
    terminal,
    box.height * 0.34f.rf,
    terminal * 0.2f.rf,
  )
  communityBar(
    family,
    look,
    value,
    false,
    zones,
    box.width - terminal * 2f.rf,
    box.height * 0.7f.rf,
    box.left,
    box.top + box.height * 0.15f.rf,
  )
}
