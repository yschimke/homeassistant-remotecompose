@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import android.graphics.Paint as AndroidPaint
import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.shaders.RemoteBrush
import androidx.compose.remote.creation.compose.shaders.RemoteShaderBrush
import androidx.compose.remote.creation.compose.shaders.linearGradient
import androidx.compose.remote.creation.compose.shaders.radialGradient
import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.asRemotePaint
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.selectIfGe
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

// Console: a 1970s broadcast desk, drawn skeuomorphically — light, depth and material, not flat
// shapes. A dial is a VU meter: a cream face backlit from behind glass, the standard VU scale with
// its red zone from 0 VU, a black needle swinging from a pivot below the face. A bar and a
// thermometer are faders: a machined metal cap riding a recessed slot lit amber behind it. A
// battery is a lamp in a metal bezel. Everything here is an original drawing of the genre; the VU
// scale marks are the ANSI C16.5 standard's.
//
// The instrument colours are the desk's own (a VU face is cream whatever the room), so they are
// constants here rather than palette roles; the value colour still comes from the caller.

private val FaceLit = Color(0xFFFFEDB8)
private val FaceEdge = Color(0xFFE2B866)
private val ScaleInk = Color(0xFF231C12)
private val ScaleRed = Color(0xFFC62A1F)
private val Bezel = Color(0xFF0E161A)
private val SlotDeep = Color(0xFF0B1216)
private val SlotLip = Color(0xFF22343D)
private val MetalLight = Color(0xFFE9ECEE)
private val MetalMid = Color(0xFFA9B0B5)
private val MetalDark = Color(0xFF5C6469)

/** 0 VU on the standard VU scale, as a fraction of its sweep: 100 of 141.25 voltage units. */
internal const val ZeroVu = 0.708f

/** The marks of the standard VU scale (−20 … +3 VU), as fractions of the sweep. */
private val VuMarks =
  floatArrayOf(0.071f, 0.224f, 0.317f, 0.398f, 0.502f, 0.563f, 0.632f, ZeroVu, 0.794f, 0.891f, 1f)

/** Indices of [VuMarks] drawn long: −20, −10, −5, 0, +3. */
private val VuLongMarks = setOf(0, 1, 3, 7, 10)

/** The needle's sweep, in degrees, centred on straight up — a VU meter swings about 100°. */
private const val VuSweep = 100f
private const val VuStart = 270f - VuSweep / 2f

private fun shaderPaint(
  scope: RemoteDrawScope,
  brush: RemoteShaderBrush,
  size: RemoteSize,
): RemotePaint =
  AndroidPaint()
    .apply {
      isAntiAlias = true
      style = AndroidPaint.Style.FILL
    }
    .asRemotePaint()
    .also { paint -> with(brush) { scope.applyTo(paint, size) } }

/** A vertical metal gradient over the box at [y] of height [h]: light at the top, dark below. */
private fun RemoteDrawScope.metalPaint(y: RemoteFloat, h: RemoteFloat): RemotePaint =
  shaderPaint(
    this,
    RemoteBrush.linearGradient(
      listOf(MetalLight.rc, MetalMid.rc, MetalDark.rc),
      RemoteOffset(0f.rf, y),
      RemoteOffset(0f.rf, y + h),
    ),
    RemoteSize(width, height),
  )

/** A point at canvas angle [degrees] (0 = +x, clockwise) at [r] from ([cx], [cy]). */
private fun polar(cx: RemoteFloat, cy: RemoteFloat, degrees: Float, r: RemoteFloat): RemoteOffset {
  val rad = Math.toRadians(degrees.toDouble())
  return RemoteOffset(cx + r * cos(rad).toFloat().rf, cy + r * sin(rad).toFloat().rf)
}

/**
 * A VU meter filling the canvas down to [baseline]: a dark bezel, a backlit cream face, the
 * standard scale with its red zone, and a needle from a pivot at the foot of the face.
 */
internal fun RemoteDrawScope.consoleArc(
  look: MeterLook,
  fraction: RemoteFloat,
  baseline: Float,
  zones: HaMeterZones,
) {
  val faceH = height * baseline.rf
  val faceW = width.min(faceH * 1.9f.rf)
  val left = (width - faceW) / 2f.rf
  val bezel = (faceW * 0.035f.rf).min(5f.rf)
  fillRound(Bezel.rc, left, 0f.rf, faceW, faceH, bezel * 2f.rf)
  val fx = left + bezel
  val fy = bezel
  val fw = faceW - bezel * 2f.rf
  val fh = faceH - bezel * 2f.rf
  // The lamp behind the face: brightest just above the pivot, warming to amber at the edges.
  val cx = width / 2f.rf
  drawRoundRect(
    shaderPaint(
      this,
      RemoteBrush.radialGradient(
        listOf(FaceLit.rc, FaceLit.rc, FaceEdge.rc),
        RemoteOffset(cx, fy + fh * 0.7f.rf),
        fw * 0.75f.rf,
      ),
      RemoteSize(width, height),
    ),
    RemoteOffset(fx, fy),
    RemoteSize(fw, fh),
    RemoteOffset(bezel, bezel),
  )
  // The pivot sits at the foot of the face; the scale is the largest arc of the sweep that fits.
  val pivotY = fy + fh * 0.96f.rf
  val halfSpan = sin(Math.toRadians((VuSweep / 2f).toDouble())).toFloat()
  val radius = (fw * (0.42f / halfSpan).rf).min(fh * 0.72f.rf)
  val oval = RemoteOffset(cx - radius, pivotY - radius)
  val ovalSize = RemoteSize(radius * 2f.rf, radius * 2f.rf)
  val hot: RemoteFloat =
    zones.hotFromLive?.let {
      selectIfGe(it, 0.999f.rf, ZeroVu.rf, remoteMax(it, 0.05f.rf).min(1f.rf))
    } ?: (zones.hotFrom ?: ZeroVu).coerceIn(0.05f, 1f).rf
  drawArc(
    strokePaint(2f, ScaleInk.rc, round = false),
    VuStart.rf,
    hot * VuSweep.rf,
    false,
    oval,
    ovalSize,
  )
  drawArc(
    strokePaint(5f, ScaleRed.rc, round = false),
    VuStart.rf + hot * VuSweep.rf,
    (1f.rf - hot) * VuSweep.rf,
    false,
    RemoteOffset(cx - radius + 3f.rf, pivotY - radius + 3f.rf),
    RemoteSize(radius * 2f.rf - 6f.rf, radius * 2f.rf - 6f.rf),
  )
  val staticHot = zones.hotFrom ?: ZeroVu
  VuMarks.forEachIndexed { i, at ->
    val deg = VuStart + at * VuSweep
    val len = radius * (if (i in VuLongMarks) 0.13f else 0.07f).rf
    val ink = if (zones.hotFromLive == null && at >= staticHot) ScaleRed else ScaleInk
    drawLine(
      strokePaint(1.5f, ink.rc, round = false),
      polar(cx, pivotY, deg, radius),
      polar(cx, pivotY, deg, radius + len),
    )
  }
  // The needle, drawn at the start of the sweep and swung by the live fraction; a faint shadow
  // falls on the face just below it.
  val pivot = RemoteOffset(cx, pivotY)
  rotate(fraction * VuSweep.rf, pivot) {
    drawLine(
      strokePaint(3f, Color.Black.copy(alpha = 0.18f).rc),
      RemoteOffset(cx + 2f.rf, pivotY + 2f.rf),
      polar(cx + 2f.rf, pivotY + 2f.rf, VuStart, radius * 1.08f.rf),
    )
    drawLine(strokePaint(2f, ScaleInk.rc), pivot, polar(cx, pivotY, VuStart, radius * 1.1f.rf))
  }
  // The pivot cover: a black bridge across the foot of the face.
  fillRound(
    Bezel.rc,
    cx - fw * 0.16f.rf,
    pivotY - fh * 0.08f.rf,
    fw * 0.32f.rf,
    fh * 0.12f.rf,
    fh * 0.04f.rf,
  )
  // Glass: a soft highlight over the top of the face.
  drawRoundRect(
    shaderPaint(
      this,
      RemoteBrush.linearGradient(
        listOf(Color.White.copy(alpha = 0.32f).rc, Color.White.copy(alpha = 0f).rc),
        RemoteOffset(0f.rf, fy),
        RemoteOffset(0f.rf, fy + fh * 0.5f.rf),
      ),
      RemoteSize(width, height),
    ),
    RemoteOffset(fx, fy),
    RemoteSize(fw, fh * 0.5f.rf),
    RemoteOffset(bezel, bezel),
  )
}

/**
 * A fader along a main axis: a recessed slot, lit [MeterLook.value] behind it up to the reading,
 * and a metal cap riding at the reading with a centre line.
 */
private fun RemoteDrawScope.fader(
  look: MeterLook,
  fraction: RemoteFloat,
  vertical: Boolean,
  start: RemoteFloat,
  len: RemoteFloat,
  cross: RemoteFloat,
  thick: RemoteFloat,
) {
  val capLen = (thick * 0.9f.rf).min(len * 0.22f.rf)
  val travel = len - capLen
  val slot = thick * 0.34f.rf
  val slotAt = cross + (thick - slot) / 2f.rf
  val lit = slot * 0.5f.rf
  val litAt = cross + (thick - lit) / 2f.rf
  val pos = travel * fraction
  if (vertical) {
    val bottom = start + len
    fillRound(SlotLip.rc, slotAt - 1f.rf, start, slot + 2f.rf, len, slot)
    fillRound(SlotDeep.rc, slotAt, start + 1f.rf, slot, len - 2f.rf, slot / 2f.rf)
    fillRound(look.value, litAt, bottom - capLen / 2f.rf - pos, lit, pos, lit / 2f.rf)
    val capY = bottom - capLen - pos
    fillRound(Color.Black.copy(alpha = 0.35f).rc, cross + 1f.rf, capY + 2f.rf, thick, capLen, 2f.rf)
    drawRoundRect(
      metalPaint(capY, capLen),
      RemoteOffset(cross, capY),
      RemoteSize(thick, capLen),
      RemoteOffset(2f.rf, 2f.rf),
    )
    fillRect(ScaleInk.rc, cross, capY + capLen / 2f.rf - 1f.rf, thick, 2f.rf)
  } else {
    fillRound(SlotLip.rc, start, slotAt - 1f.rf, len, slot + 2f.rf, slot)
    fillRound(SlotDeep.rc, start + 1f.rf, slotAt, len - 2f.rf, slot, slot / 2f.rf)
    fillRound(look.value, start + capLen / 2f.rf, litAt, pos, lit, lit / 2f.rf)
    val capX = start + pos
    fillRound(Color.Black.copy(alpha = 0.35f).rc, capX + 2f.rf, cross + 1f.rf, capLen, thick, 2f.rf)
    drawRoundRect(
      metalPaint(cross, thick),
      RemoteOffset(capX, cross),
      RemoteSize(capLen, thick),
      RemoteOffset(2f.rf, 2f.rf),
    )
    fillRect(ScaleInk.rc, capX + capLen / 2f.rf - 1f.rf, cross, 2f.rf, thick)
  }
}

internal fun RemoteDrawScope.consoleBar(look: MeterLook, fraction: RemoteFloat, vertical: Boolean) {
  if (vertical) fader(look, fraction, true, 0f.rf, height, 0f.rf, width)
  else fader(look, fraction, false, 0f.rf, width, 0f.rf, height)
}

/** A vertical fader beside a silkscreened scale, at 1 : 3; the scale's marks past hot are red. */
internal fun RemoteDrawScope.consoleThermometer(
  look: MeterLook,
  fraction: RemoteFloat,
  zones: HaMeterZones,
) {
  val box = FitBox(width, height, 1f / 3f)
  val scaleW = box.width * 0.32f.rf
  val faderX = box.left + box.width * 0.4f.rf
  val faderW = box.width * 0.6f.rf
  val capLen = (faderW * 0.9f.rf).min(box.height * 0.22f.rf)
  val stroke = (box.width * 0.045f.rf).min(2f.rf)
  for (i in 0..10) {
    val at = i / 10f
    val y = box.bottom - capLen / 2f.rf - (box.height - capLen) * at.rf
    val len = if (i % 5 == 0) scaleW else scaleW * 0.5f.rf
    val hot = zones.hotFrom
    val ink: RemoteColor =
      when {
        zones.hotFromLive != null ->
          at.rf.isGreaterThanOrEqualTo(zones.hotFromLive).select(ScaleRed.rc, look.muted)
        hot != null && at >= hot -> ScaleRed.rc
        else -> look.muted
      }
    fillRect(ink, box.left + scaleW - len, y - stroke / 2f.rf, len, stroke)
  }
  fader(look, fraction, true, box.top, box.height, faderX, faderW)
}

/**
 * A pilot lamp in a metal bezel: a machined frame and terminal, a dark window, and the charge lit
 * behind it in the reading's colour — red below the low mark — under a glass highlight.
 */
internal fun RemoteDrawScope.consoleBattery(
  look: MeterLook,
  fraction: RemoteFloat,
  zones: HaMeterZones,
) {
  val box = FitBox(width, height, 2.3f)
  val nubW = box.width * 0.06f.rf
  val bodyW = box.width - nubW
  val frame = box.height * 0.14f.rf
  val corner = box.height * 0.18f.rf
  drawRoundRect(
    metalPaint(box.top, box.height),
    RemoteOffset(box.left, box.top),
    RemoteSize(bodyW, box.height),
    RemoteOffset(corner, corner),
  )
  drawRoundRect(
    metalPaint(box.top + box.height * 0.3f.rf, box.height * 0.4f.rf),
    RemoteOffset(box.left + bodyW - 1f.rf, box.top + box.height * 0.3f.rf),
    RemoteSize(nubW + 1f.rf, box.height * 0.4f.rf),
    RemoteOffset(nubW / 2f.rf, nubW / 2f.rf),
  )
  val wx = box.left + frame
  val wy = box.top + frame
  val ww = bodyW - frame * 2f.rf
  val wh = box.height - frame * 2f.rf
  fillRound(SlotDeep.rc, wx, wy, ww, wh, corner / 2f.rf)
  val fill: RemoteColor =
    zones.lowBelow?.let { fraction.isGreaterThanOrEqualTo(it.rf).select(look.value, ScaleRed.rc) }
      ?: look.value
  val inset = 2f.rf
  fillRound(
    fill,
    wx + inset,
    wy + inset,
    (ww - inset * 2f.rf) * fraction,
    wh - inset * 2f.rf,
    corner / 3f.rf,
  )
  drawRoundRect(
    shaderPaint(
      this,
      RemoteBrush.linearGradient(
        listOf(Color.White.copy(alpha = 0.3f).rc, Color.White.copy(alpha = 0f).rc),
        RemoteOffset(0f.rf, wy),
        RemoteOffset(0f.rf, wy + wh * 0.6f.rf),
      ),
      RemoteSize(width, height),
    ),
    RemoteOffset(wx, wy),
    RemoteSize(ww, wh * 0.5f.rf),
    RemoteOffset(corner / 2f.rf, corner / 2f.rf),
  )
}

/**
 * A rotary control's face for a 270° dial (thermostat, light) fitting [side] at ([left], [top]): a
 * dark knurled knob with a lamp ring of [LampCount] dots round it, lit in [accent] up to
 * [fraction], and a red index lamp at [target], dark while [targetVisible] is false. The centre is
 * left for the dial's text.
 */
internal fun RemoteDrawScope.consoleDial(
  accent: RemoteColor,
  fraction: RemoteFloat,
  target: RemoteFloat?,
  left: RemoteFloat,
  top: RemoteFloat,
  side: RemoteFloat,
  targetVisible: RemoteBoolean? = null,
) {
  val r = side / 2f.rf
  val cx = left + r
  val cy = top + r
  // The lamps: unlit ones are dark glass with a faint rim, lit ones the accent.
  val ringR = r * 0.92f.rf
  val lampR = (r * 0.035f.rf).min(5f.rf)
  for (i in 0 until LampCount) {
    val at = i / (LampCount - 1f)
    val p = polar(cx, cy, 135f + at * 270f, ringR)
    val lit = fraction.isGreaterThanOrEqualTo((at - 0.001f).rf)
    drawCircle(fillPaint(SlotLip.rc), lampR * 1.35f.rf, p)
    drawCircle(fillPaint(lit.select(accent, SlotDeep.rc)), lampR, p)
  }
  if (target != null) {
    // The index lamp rides the ring at the target; drawn at 135° and swung by the target.
    rotate(target * 270f.rf, RemoteOffset(cx, cy)) {
      val lamp = targetVisible?.select(ScaleRed.rc, Color.Transparent.rc) ?: ScaleRed.rc
      drawCircle(fillPaint(lamp), lampR * 1.5f.rf, polar(cx, cy, 135f, ringR))
    }
  }
  // The knob: a soft drop shadow, a knurled metal skirt, and a dark cap lit from the top left.
  val knobR = r * 0.78f.rf
  drawCircle(
    fillPaint(Color.Black.copy(alpha = 0.35f).rc),
    knobR,
    RemoteOffset(cx + 2f.rf, cy + 4f.rf),
  )
  drawCircle(metalPaint(cy - knobR, knobR * 2f.rf), knobR, RemoteOffset(cx, cy))
  val knurl = strokePaint(1.5f, MetalDark.rc, round = false)
  for (i in 0 until 48) {
    val deg = i * 7.5f
    drawLine(knurl, polar(cx, cy, deg, knobR * 0.9f.rf), polar(cx, cy, deg, knobR))
  }
  val capR = knobR * 0.88f.rf
  drawCircle(
    shaderPaint(
      this,
      RemoteBrush.radialGradient(
        listOf(Color(0xFF3E5866).rc, Color(0xFF1B2A32).rc, Color(0xFF0E171C).rc),
        RemoteOffset(cx - capR * 0.35f.rf, cy - capR * 0.45f.rf),
        capR * 1.6f.rf,
      ),
      RemoteSize(width, height),
    ),
    capR,
    RemoteOffset(cx, cy),
  )
  // The pointer line on the cap, swung with the value.
  rotate(fraction * 270f.rf, RemoteOffset(cx, cy)) {
    drawLine(
      strokePaint(3f, MetalLight.rc),
      polar(cx, cy, 135f, capR * 0.62f.rf),
      polar(cx, cy, 135f, capR * 0.94f.rf),
    )
  }
}

/** Lamps round a [consoleDial]. */
private const val LampCount = 21

/** The metal colours a [HaSurfaceMaterial.PaintedMetal] control cap is machined in. */
internal val ConsoleMetal: List<Color> = listOf(MetalLight, MetalMid, MetalDark)

/** The recess a painted-metal switch slides in. */
internal val ConsoleSlot: Color = SlotDeep
