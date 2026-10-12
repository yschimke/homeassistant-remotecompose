@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import android.graphics.Paint as AndroidPaint
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.fillMaxHeight
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.asRemotePaint
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.state.selectIfGe
import androidx.compose.remote.creation.compose.state.selectIfLt
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * HA `gauge` card — half-circle dial.
 *
 * ```
 *      ╱──────────╲
 *     │   45 °C    │   ← arc (grey track + severity overlay) + value text
 *      ╲──────────╱
 *        Coolant
 *      0       100
 * ```
 *
 * The arc sweep is derived from the named `<entityId>.numeric_state` binding (animated through
 * [animateRemoteFloat]) so live value updates tween without re-encoding. Severity bands tint the
 * value arc green / yellow / red (matching HA's `gauge.severity` config); the document picks the
 * active band from the live value, so a reading crossing a band needs no re-encode.
 */
@Composable
@RemoteComposable
fun RemoteHaGauge(data: HaGaugeData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  val clickable =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier

  // Live numeric value from the entity binding, animated so each host
  // update tweens between successive values instead of snapping.
  val animatedSweep = animatedGaugeSweep(data)
  val severityColor = gaugeSeverityColor(data)

  RemoteBox(
    modifier =
      modifier
        .then(clickable)
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 12.rdp)
  ) {
    RemoteColumn(
      modifier = RemoteModifier.fillMaxWidth(),
      verticalArrangement = RemoteArrangement.spacedBy(4.rdp),
      horizontalAlignment = RemoteAlignment.CenterHorizontally,
    ) {
      RemoteCanvas(modifier = RemoteModifier.fillMaxWidth().height(72.rdp)) {
        val w = width
        val h = height
        val stroke = 10f.rf
        val pad = (stroke / 2f.rf) + 2f.rf
        val diameter = (h - pad) * 2f.rf
        val left = (w - diameter) / 2f.rf
        val top = h - (diameter / 2f.rf)
        val topLeft = RemoteOffset(left, top)
        val arcSize = RemoteSize(diameter, diameter)

        val track =
          AndroidPaint()
            .apply {
              isAntiAlias = true
              style = AndroidPaint.Style.STROKE
              strokeWidth = 10f
              strokeCap = AndroidPaint.Cap.ROUND
            }
            .asRemotePaint()
            .apply { color = theme.divider }
        drawArc(track, 180f.rf, 180f.rf, false, topLeft, arcSize)

        val value =
          AndroidPaint()
            .apply {
              isAntiAlias = true
              style = AndroidPaint.Style.STROKE
              strokeWidth = 10f
              strokeCap = AndroidPaint.Cap.ROUND
            }
            .asRemotePaint()
            .apply { color = severityColor }
        drawArc(value, 180f.rf, animatedSweep, false, topLeft, arcSize)
      }

      RemoteText(
        text = data.valueText,
        color = theme.primaryText,
        fontSize = 22.rsp,
        fontWeight = FontWeight.SemiBold,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      RemoteText(
        text = entityName(data.entityId, data.name),
        color = theme.secondaryText,
        fontSize = 13.rsp,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (data.unit != null) {
        RemoteText(
          text = cardText("range", gaugeRangeText(data.min, data.max, data.unit)),
          color = theme.secondaryText,
          fontSize = 11.rsp,
          style = RemoteTextStyle.Default,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

private const val GaugeAnimationSeconds = 0.45f

/**
 * Stacked Fixed-mode gauge variant — the dominant Fixed-mode tier. Renders the arc as a backdrop
 * (top-anchored half-circle, capped so its visible base lands at ~60 % of the cell height) and
 * overlays value · name · range in the open interior below. One composable covers everything from a
 * 1×1 launcher chip up to a 3×2 dashboard tile and Wear L — the same proportional crop scales
 * naturally across sizes; secondary text lines ellipsise on cells that can't fit them. Very short
 * cells (Wear S) skip the stacked overlay and fall through to [RemoteHaGaugeWide].
 */
@Composable
@RemoteComposable
fun RemoteHaGaugeStacked(data: HaGaugeData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  val animatedSweep = animatedGaugeSweep(data)
  val severityColor = gaugeSeverityColor(data)
  val clickable =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier

  RemoteBox(
    modifier =
      modifier.then(clickable).then(cardChrome(theme.cardBackground, theme.divider)).padding(8.rdp),
    contentAlignment = RemoteAlignment.BottomCenter,
  ) {
    // Arc fills the box, but the diameter is capped so the
    // visible half (= diameter / 2) only reaches ~60 % of the
    // cell height. That leaves the lower ~40 % for the text
    // overlay regardless of the cell's aspect ratio.
    RemoteCanvas(modifier = RemoteModifier.fillMaxSize()) {
      val w = width
      val h = height
      val stroke = 8f.rf
      val pad = (stroke / 2f.rf) + 2f.rf
      val arcBottomY = h * 0.6f.rf
      val byHeight = (arcBottomY - pad) * 2f.rf
      val byWidth = w - pad * 2f.rf
      val diameter = byHeight.min(byWidth)
      val left = (w - diameter) / 2f.rf
      val top = arcBottomY - (diameter / 2f.rf)
      val topLeft = RemoteOffset(left, top)
      val arcSize = RemoteSize(diameter, diameter)

      val track =
        AndroidPaint()
          .apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = 8f
            strokeCap = AndroidPaint.Cap.ROUND
          }
          .asRemotePaint()
          .apply { color = theme.divider }
      drawArc(track, 180f.rf, 180f.rf, false, topLeft, arcSize)

      val value =
        AndroidPaint()
          .apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = 8f
            strokeCap = AndroidPaint.Cap.ROUND
          }
          .asRemotePaint()
          .apply { color = severityColor }
      drawArc(value, 180f.rf, animatedSweep, false, topLeft, arcSize)
    }
    RemoteColumn(
      modifier = RemoteModifier.fillMaxWidth(),
      verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
      horizontalAlignment = RemoteAlignment.CenterHorizontally,
    ) {
      RemoteText(
        text = data.valueText,
        color = theme.primaryText,
        fontSize = 18.rsp,
        fontWeight = FontWeight.SemiBold,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      RemoteText(
        text = entityName(data.entityId, data.name),
        color = theme.secondaryText,
        fontSize = 12.rsp,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (data.unit != null) {
        RemoteText(
          text = cardText("range", gaugeRangeText(data.min, data.max, data.unit)),
          color = theme.secondaryText,
          fontSize = 10.rsp,
          style = RemoteTextStyle.Default,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/**
 * Wide-thin Fixed-mode gauge variant. Half-arc on the left at square aspect, value + name stacked
 * on the right. Targets the very short `200×60` Wear S chip where the [RemoteHaGaugeStacked]
 * backdrop doesn't have enough vertical room to host the arc + text overlay.
 */
@Composable
@RemoteComposable
fun RemoteHaGaugeWide(data: HaGaugeData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  val animatedSweep = animatedGaugeSweep(data)
  val severityColor = gaugeSeverityColor(data)
  val clickable =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier

  RemoteRow(
    modifier =
      modifier
        .then(clickable)
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 8.rdp, vertical = 6.rdp),
    verticalAlignment = RemoteAlignment.Bottom,
    horizontalArrangement = RemoteArrangement.spacedBy(8.rdp),
  ) {
    // Arc canvas sized off the row height so the half-arc grows
    // with taller cells (Wear L vs Wear S). The text column gets
    // the rest of the width via `weight()` so value / name aren't
    // clipped on narrower 2×1 chips.
    RemoteCanvas(modifier = RemoteModifier.fillMaxHeight().width(72.rdp)) {
      val w = width
      val h = height
      val stroke = 6f.rf
      val pad = stroke / 2f.rf
      val byHeight = (h - pad) * 2f.rf
      val byWidth = w - pad * 2f.rf
      val diameter = byHeight.min(byWidth)
      val left = (w - diameter) / 2f.rf
      val arcTop = h - (diameter / 2f.rf) - pad
      val topLeft = RemoteOffset(left, arcTop)
      val arcSize = RemoteSize(diameter, diameter)

      val track =
        AndroidPaint()
          .apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = 6f
            strokeCap = AndroidPaint.Cap.ROUND
          }
          .asRemotePaint()
          .apply { color = theme.divider }
      drawArc(track, 180f.rf, 180f.rf, false, topLeft, arcSize)

      val value =
        AndroidPaint()
          .apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = 6f
            strokeCap = AndroidPaint.Cap.ROUND
          }
          .asRemotePaint()
          .apply { color = severityColor }
      drawArc(value, 180f.rf, animatedSweep, false, topLeft, arcSize)
    }
    RemoteColumn(
      modifier = RemoteModifier.weight(1f).padding(bottom = 4.rdp),
      verticalArrangement = RemoteArrangement.Center,
      horizontalAlignment = RemoteAlignment.Start,
    ) {
      RemoteText(
        text = data.valueText,
        color = theme.primaryText,
        fontSize = 16.rsp,
        fontWeight = FontWeight.SemiBold,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      RemoteText(
        text = entityName(data.entityId, data.name),
        color = theme.secondaryText,
        fontSize = 11.rsp,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun animatedGaugeSweep(data: HaGaugeData): RemoteFloat {
  val min = cardFloat("min", data.min.toFloat())
  val max = cardFloat("max", data.max.toFloat())
  // Same floor on the span as before, now evaluated in-document.
  val span = (max - min).let { selectIfLt(it, 0.0001f.rf, 0.0001f.rf, it) }
  val animatedValue: RemoteFloat =
    animateRemoteFloat(
      data.value,
      durationSeconds = GaugeAnimationSeconds,
      easing = RcEasing.Standard,
    )
  return (animatedValue - min) / span * 180f.rf
}

/**
 * The value arc's colour, picked in-document from the live value: the band whose threshold is the
 * highest one the value reaches ([HaGaugeBands]), or the no-severity blue when it reaches none. An
 * unset threshold is [UnsetThreshold], which no value reaches.
 */
@Composable
private fun gaugeSeverityColor(data: HaGaugeData): RemoteColor {
  val bands = data.severity
  val value = data.value
  val green = cardFloat("severity_green", bands.green?.toFloat() ?: UnsetThreshold)
  val yellow = cardFloat("severity_yellow", bands.yellow?.toFloat() ?: UnsetThreshold)
  val red = cardFloat("severity_red", bands.red?.toFloat() ?: UnsetThreshold)
  val below = (-UnsetThreshold).rf
  // reached(t) is t when the value reaches it, else far below every threshold.
  fun reached(t: RemoteFloat) = selectIfGe(value, t, t, below)
  val top = maxOf(maxOf(reached(green), reached(yellow)), reached(red))
  // Equal thresholds resolve red, then yellow, then green, as the converter always did.
  return top
    .isEqualTo(red)
    .select(
      SeverityCritical.rc,
      top
        .isEqualTo(yellow)
        .select(
          SeverityWarning.rc,
          top.isEqualTo(green).select(SeverityNormal.rc, SeverityNone.rc),
        ),
    )
}

private fun maxOf(a: RemoteFloat, b: RemoteFloat): RemoteFloat = -((-a).min(-b))

/** Threshold for a severity band the card doesn't set: no reading reaches it. */
private const val UnsetThreshold = 1e30f

private val SeverityNone = Color(0xFF03A9F4)
private val SeverityNormal = Color(0xFF43A047)
private val SeverityWarning = Color(0xFFFFA000)
private val SeverityCritical = Color(0xFFE53935)

/** The gauge's range line, e.g. `0 – 100 °C`. */
fun gaugeRangeText(min: Double, max: Double, unit: String?): String =
  "${formatRange(min)} – ${formatRange(max)} ${unit.orEmpty()}"

private fun formatRange(d: Double): String =
  if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(d)
