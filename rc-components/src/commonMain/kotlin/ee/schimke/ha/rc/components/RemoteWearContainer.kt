@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.drawWithContent
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.shaders.RemoteBrush
import androidx.compose.remote.creation.compose.shaders.horizontalGradient
import androidx.compose.remote.creation.compose.shaders.linearGradient
import androidx.compose.remote.creation.compose.shaders.radialGradient
import androidx.compose.remote.creation.compose.shaders.sweepGradient
import androidx.compose.remote.creation.compose.shaders.verticalGradient
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteDp
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.clamp
import androidx.compose.remote.creation.compose.state.max
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.PaintingStyle

/**
 * A card whose Wear container is a level: the container shows [entityId]'s reading, from [min] to
 * [max], so the whole widget reads as a battery, a water level or a percentage at a glance. A
 * ring's reading runs clockwise round the container from the top; a fill grows from the start for a
 * battery or a percentage and rises from the bottom for humidity.
 *
 * The level is path 1 of `live-card-updates.md`: it binds `<entityId>.numeric_state` and computes
 * its fraction in-document, so a host that pushes values moves it with no re-encode and one that
 * bakes them (a Wear widget) bakes it. A non-numeric state (unavailable, unknown) shows no level.
 *
 * Which cards are levels is decided from the entity's `device_class` and unit alone ([of]), never
 * its value, so a template captured for a card shape always agrees with the card. A null [entityId]
 * is a constant level at [value], for a preview.
 */
data class HaWearLevel(
  val entityId: String?,
  val kind: HaMeasureKind,
  val min: Float,
  val max: Float,
  val value: Float,
) {
  /** Whether a fill rises from the bottom rather than growing from the start. */
  val vertical: Boolean
    get() = kind == HaMeasureKind.Humidity

  companion object {
    /**
     * The level for a card about sensor [entityId] with [deviceClass] and [unit], at [value]; null
     * for anything that isn't a battery, humidity or percentage sensor: readings on a 0–100 scale
     * of their own. A temperature has no such scale, so it isn't one.
     */
    fun of(entityId: String?, deviceClass: String?, unit: String?, value: Float): HaWearLevel? {
      if (entityId == null || !entityId.startsWith("sensor.")) return null
      val kind = HaMeasureKind.of(deviceClass, unit)
      if (kind !in LEVEL_KINDS) return null
      val range = kind.defaultRange(unit)
      return HaWearLevel(entityId, kind, range.start.toFloat(), range.endInclusive.toFloat(), value)
    }

    private val LEVEL_KINDS =
      setOf(
        HaMeasureKind.Battery,
        HaMeasureKind.Humidity,
        HaMeasureKind.Percentage,
      )
  }
}

/**
 * A Wear widget container's content: [fill], then [background]'s layers, then [level] in [look],
 * then [background]'s edge, with [content] over them. The container's padding is the caller's, so
 * all of them use the whole container under it.
 *
 * The container's shape is the host's and stays it: everything here is drawn to fit inside the
 * rounded rect of [cornerRadius], nothing is clipped by it or drawn outside it. Fills and glows are
 * that rounded rect; bands, edges and a ring follow its outline inwards ([outline]); a fill level
 * is the rounded rect cut straight along its reading. [cornerRadius] is whatever the host knows:
 * the Wear template's named `wear.container.corner_radius`, a widget frame's own value in the Wear
 * app, or a preview's approximation.
 */
@Composable
fun RemoteWearContainer(
  background: HaWearBackground?,
  fill: RemoteColor,
  cornerRadius: RemoteDp,
  level: HaWearLevel?,
  look: HaWearLevelLook,
  modifier: RemoteModifier = RemoteModifier,
  content: @Composable () -> Unit,
) {
  val cornerPx = cornerRadius.toPx()
  val gapPx = SEGMENT_GAP_DP.rdp.toPx()
  val markerPx = MARKER_DP.rdp.toPx()
  val ringInsetPx = RING_INSET_DP.rdp.toPx()
  val ringPx = look.ringDp.rdp.toPx()
  val edgeWidthPx = background?.edge?.widthDp?.rdp?.toPx()
  val edgeInsetPx = background?.edge?.insetDp?.rdp?.toPx()
  val bandPx =
    background?.layers.orEmpty().filterIsInstance<HaWearBand>().map { it.thicknessDp.rdp.toPx() }
  val fraction = level?.let {
    val value = LiveValues.numericState(it.entityId, it.value)
    animateRemoteFloat(clamp((value - it.min.rf) / (it.max - it.min).rf, 0f, 1f), LEVEL_SECONDS)
  }
  RemoteBox(
    modifier =
      modifier.fillMaxSize().drawWithContent {
        val corner = RemoteOffset(cornerPx, cornerPx)
        drawRoundRect(RemotePaint { color = fill }, cornerRadius = corner)
        var band = 0
        for (layer in background?.layers.orEmpty()) {
          if (layer is HaWearBand) {
            val thickness = bandPx[band++]
            outline(cornerPx, 0f.rf, thickness).drawSide(layer, thickness)
          } else {
            drawRoundRect(shaded(layer), cornerRadius = corner)
          }
        }
        if (level != null && fraction != null) {
          if (look.ringDp > 0f) {
            drawRing(look, fraction, outline(cornerPx, ringInsetPx, ringPx), ringPx)
          } else {
            drawFill(level, look, fraction, cornerPx, gapPx, markerPx)
          }
        }
        background?.edge?.let { edge ->
          val paint = stroke(edgeWidthPx!!, edge.colors.first().rc)
          if (edge.colors.size > 1) {
            with(RemoteBrush.sweepGradient(edge.colors.map { it.rc })) { applyTo(paint, size) }
          }
          outline(cornerPx, edgeInsetPx!!, edgeWidthPx).drawAll(paint)
        }
        drawContent()
      }
  ) {
    content()
  }
}

private fun RemoteDrawScope.shaded(layer: HaWearLayer): RemotePaint {
  val brush =
    when (layer) {
      is HaWearGradient -> {
        val stops = layer.colors.map { it.rc }
        if (layer.vertical) RemoteBrush.verticalGradient(stops)
        else RemoteBrush.horizontalGradient(stops)
      }
      is HaWearDiagonal ->
        RemoteBrush.linearGradient(
          layer.colors.map { it.rc },
          RemoteOffset(0f.rf, 0f.rf),
          RemoteOffset(size.width, size.height),
        )
      is HaWearGlow ->
        RemoteBrush.radialGradient(
          layer.colors.map { it.rc },
          center = RemoteOffset(size.width * layer.x, size.height * layer.y),
          radius = size.width * layer.radius,
        )
      is HaWearBand -> error("a band is a stroke")
    }
  return RemotePaint().also { paint -> with(brush) { applyTo(paint, size) } }
}

private fun stroke(width: RemoteFloat, color: RemoteColor) = RemotePaint {
  style = PaintingStyle.Stroke
  strokeWidth = width
  this.color = color
}

/**
 * The container's outline, moved [inset] inwards and then by half of [width], so a stroke [width]
 * wide drawn along it lies [inset] inside the container, whatever its corner radius: a pill, a
 * round watch's 26dp container or a square one's 4dp.
 *
 * It runs clockwise from the middle of the top edge, through nine pieces: the top edge's right
 * half, the top end corner, the end edge, the bottom end corner, the bottom edge, the bottom start
 * corner, the start edge, the top start corner and the top edge's left half. Positions along it are
 * fractions of its length, so any range of it can be drawn ([drawRange]) — a reading's share of a
 * ring, one side for a band.
 */
private class Outline(
  private val scope: RemoteDrawScope,
  corner: RemoteFloat,
  inset: RemoteFloat,
  width: RemoteFloat,
) {
  private val o = inset + width / 2f.rf
  private val r = max(corner - o, 0f)
  private val w = scope.size.width
  private val h = scope.size.height
  private val left = o
  private val top = o
  private val right = w - o
  private val bottom = h - o
  private val horizontal = max(right - left - r * 2f, 0f)
  private val vertical = max(bottom - top - r * 2f, 0f)
  private val arc = r * QUARTER_TURN
  private val length = (horizontal + vertical) * 2f + arc * 4f

  private sealed interface Piece {
    val length: RemoteFloat
  }

  private class Line(
    val from: RemoteOffset,
    val to: RemoteOffset,
    override val length: RemoteFloat,
  ) : Piece

  private class Arc(
    val centre: RemoteOffset,
    val startAngle: Float,
    override val length: RemoteFloat,
  ) : Piece

  private val pieces: List<Piece> =
    listOf(
      Line(RemoteOffset(w / 2f.rf, top), RemoteOffset(right - r, top), horizontal / 2f.rf),
      Arc(RemoteOffset(right - r, top + r), 270f, arc),
      Line(RemoteOffset(right, top + r), RemoteOffset(right, bottom - r), vertical),
      Arc(RemoteOffset(right - r, bottom - r), 0f, arc),
      Line(RemoteOffset(right - r, bottom), RemoteOffset(left + r, bottom), horizontal),
      Arc(RemoteOffset(left + r, bottom - r), 90f, arc),
      Line(RemoteOffset(left, bottom - r), RemoteOffset(left, top + r), vertical),
      Arc(RemoteOffset(left + r, top + r), 180f, arc),
      Line(RemoteOffset(left + r, top), RemoteOffset(w / 2f.rf, top), horizontal / 2f.rf),
    )

  /**
   * The pieces of one [HaWearBand.Side]: the top edge (its two halves), the bottom edge, or the
   * start corners and edge.
   */
  private fun piecesOf(side: HaWearBand.Side): List<Int> =
    when (side) {
      HaWearBand.Side.Top -> listOf(8, 0)
      HaWearBand.Side.Bottom -> listOf(4)
      HaWearBand.Side.Start -> listOf(5, 6, 7)
    }

  /** Strokes the whole outline. */
  fun drawAll(paint: RemotePaint) {
    scope.drawRoundRect(
      paint,
      RemoteOffset(left, top),
      RemoteSize(right - left, bottom - top),
      RemoteOffset(r, r),
    )
  }

  /** Strokes [band] [width] wide: along the top or bottom edge, or round the start. */
  fun drawSide(band: HaWearBand, width: RemoteFloat) {
    val paint = stroke(width, band.color.rc)
    for (i in piecesOf(band.side)) drawPiece(pieces[i], paint, 0f.rf, 1f.rf)
  }

  /** Strokes the outline from [from] to [to], fractions of its length clockwise from the top. */
  fun drawRange(paint: RemotePaint, from: RemoteFloat, to: RemoteFloat) {
    var start: RemoteFloat = 0f.rf
    for (piece in pieces) {
      // The share of this piece the range covers, guarded against a piece of no length (a pill's
      // straight ends).
      val span = max(piece.length, MIN_PIECE)
      val a = clamp((from * length - start) / span, 0f, 1f)
      val b = clamp((to * length - start) / span, 0f, 1f)
      drawPiece(piece, paint, a, b)
      start = start + piece.length
    }
  }

  private fun drawPiece(piece: Piece, paint: RemotePaint, a: RemoteFloat, b: RemoteFloat) {
    when (piece) {
      is Line -> {
        val dx = piece.to.x - piece.from.x
        val dy = piece.to.y - piece.from.y
        scope.drawLine(
          paint,
          RemoteOffset(piece.from.x + dx * a, piece.from.y + dy * a),
          RemoteOffset(piece.from.x + dx * b, piece.from.y + dy * b),
        )
      }
      is Arc ->
        scope.drawArc(
          paint,
          piece.startAngle.rf + a * 90f,
          (b - a) * 90f,
          false,
          RemoteOffset(piece.centre.x - r, piece.centre.y - r),
          RemoteSize(r * 2f, r * 2f),
        )
    }
  }
}

private fun RemoteDrawScope.outline(corner: RemoteFloat, inset: RemoteFloat, width: RemoteFloat) =
  Outline(this, corner, inset, width)

/**
 * [level] as a ring round the container ([RING_INSET_DP] in, [width] wide): a faint track the whole
 * way round, and the reading in full strength from the top, clockwise — or a dial's pointer at the
 * reading. The ring lies in the container's padding, outside the card's text, so it needs no
 * contrast cap.
 */
private fun RemoteDrawScope.drawRing(
  look: HaWearLevelLook,
  fraction: RemoteFloat,
  ring: Outline,
  width: RemoteFloat,
) {
  if (look.track) ring.drawAll(stroke(width, look.color.copy(alpha = look.alpha * TRACK_SHARE).rc))
  val reading = stroke(width, look.color.copy(alpha = look.alpha).rc)
  if (look.sweep.isNotEmpty()) {
    with(RemoteBrush.sweepGradient(look.sweep.map { it.copy(alpha = look.alpha).rc })) {
      applyTo(reading, size)
    }
  }
  val from = if (look.pointer) max(fraction - POINTER_SHARE, 0f) else 0f.rf
  ring.drawRange(reading, from, fraction)
}

/**
 * [level] as a fill: the container's rounded rect, cut straight along the reading — from the start
 * for a battery or a percentage, rising from the bottom for humidity — so it keeps the container's
 * corners wherever it reaches them. Cut into [HaWearLevelLook.segments] cells, each the rounded
 * rect cut to the cell.
 */
private fun RemoteDrawScope.drawFill(
  level: HaWearLevel,
  look: HaWearLevelLook,
  fraction: RemoteFloat,
  corner: RemoteFloat,
  gap: RemoteFloat,
  marker: RemoteFloat,
) {
  val width = size.width
  val height = size.height
  val radius = RemoteOffset(corner, corner)
  val solid = RemotePaint { color = look.color.copy(alpha = look.alpha).rc }
  // The container's rounded rect within the straight cut from [from] to [to] along the fill.
  fun cut(paint: RemotePaint, from: RemoteFloat, to: RemoteFloat) {
    if (level.vertical) {
      clipRect(0f.rf, height - to, width, height - from) {
        drawRoundRect(paint, cornerRadius = radius)
      }
    } else {
      clipRect(from, 0f.rf, to, height) { drawRoundRect(paint, cornerRadius = radius) }
    }
  }
  val extent = if (level.vertical) height else width
  if (look.segments > 0) {
    val n = look.segments
    val cell = (extent - gap * (n - 1).toFloat()) / n.toFloat()
    val ghost = RemotePaint { color = look.color.copy(alpha = look.alpha * GHOST_SHARE).rc }
    for (i in 0 until n) {
      val lit = clamp(fraction * n.toFloat() - i.toFloat(), 0f, 1f)
      val start = (cell + gap) * i.toFloat()
      if (look.ghosts) cut(ghost, start, start + cell)
      cut(solid, start, start + cell * lit)
    }
    return
  }
  val reach = extent * fraction
  val paint =
    if (look.fades) {
      val from = if (level.vertical) RemoteOffset(0f.rf, height) else RemoteOffset(0f.rf, 0f.rf)
      val to =
        if (level.vertical) RemoteOffset(0f.rf, height - reach) else RemoteOffset(reach, 0f.rf)
      RemotePaint().also { paint ->
        with(
          RemoteBrush.linearGradient(
            listOf(look.color.copy(alpha = 0f).rc, look.color.copy(alpha = look.alpha).rc),
            from,
            to,
          )
        ) {
          applyTo(paint, size)
        }
      }
    } else solid
  cut(paint, 0f.rf, reach)
  if (look.marker) {
    // The fill's leading edge, a line across the container that stops where its outline does.
    val line = RemotePaint { color = look.color.copy(alpha = look.markerAlpha).rc }
    cut(line, reach - marker, reach)
  }
}

/** How long a level takes to move to a new reading. A first reading is drawn in place. */
private const val LEVEL_SECONDS = 0.6f
private const val SEGMENT_GAP_DP = 2f
private const val MARKER_DP = 2f

/** A ring's distance from the container's outline. */
private const val RING_INSET_DP = 4f

/** A dial pointer's length, as a share of the ring. */
private const val POINTER_SHARE = 0.05f

/** How strongly a ring's track shows, as a share of its reading's alpha. */
private const val TRACK_SHARE = 0.22f

/** How strongly an unlit cell shows, as a share of a lit one's alpha. */
private const val GHOST_SHARE = 0.3f

/** A quarter turn's arc length per unit radius. */
private const val QUARTER_TURN = (kotlin.math.PI / 2).toFloat()

/** The length a piece of no length is divided by, so its share is never NaN. */
private const val MIN_PIECE = 0.001f
