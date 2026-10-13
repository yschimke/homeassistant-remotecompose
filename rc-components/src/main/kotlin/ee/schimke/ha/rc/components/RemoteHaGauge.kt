@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
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
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.selectIfGe
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
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
  val fraction = animatedGaugeFraction(data)
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
      RemoteHaArcMeter(
        fraction,
        severityColor,
        RemoteModifier.fillMaxWidth().height(72.rdp),
        strokeWidthPx = 10f,
      )

      RemoteText(
        text = data.valueText,
        color = theme.primaryText,
        fontSize = HaType.Value,
        fontWeight = FontWeight.SemiBold,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      RemoteText(
        text = entityName(data.entityId, data.name),
        color = theme.secondaryText,
        fontSize = HaType.Body,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (data.unit != null) {
        RemoteText(
          text = cardText("range", gaugeRangeText(data.min, data.max, data.unit)),
          color = theme.secondaryText,
          fontSize = HaType.Caption,
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
  val fraction = animatedGaugeFraction(data)
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
    RemoteHaArcMeter(
      fraction,
      severityColor,
      RemoteModifier.fillMaxSize(),
      strokeWidthPx = 8f,
      baseline = 0.6f,
    )
    RemoteColumn(
      modifier = RemoteModifier.fillMaxWidth(),
      verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
      horizontalAlignment = RemoteAlignment.CenterHorizontally,
    ) {
      RemoteText(
        text = data.valueText,
        color = theme.primaryText,
        fontSize = HaType.ValueSmall,
        fontWeight = FontWeight.SemiBold,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      RemoteText(
        text = entityName(data.entityId, data.name),
        color = theme.secondaryText,
        fontSize = HaType.Label,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (data.unit != null) {
        RemoteText(
          text = cardText("range", gaugeRangeText(data.min, data.max, data.unit)),
          color = theme.secondaryText,
          fontSize = HaType.Micro,
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
  val fraction = animatedGaugeFraction(data)
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
    RemoteHaArcMeter(
      fraction,
      severityColor,
      RemoteModifier.fillMaxHeight().width(72.rdp),
      strokeWidthPx = 6f,
    )
    RemoteColumn(
      modifier = RemoteModifier.weight(1f).padding(bottom = 4.rdp),
      verticalArrangement = RemoteArrangement.Center,
      horizontalAlignment = RemoteAlignment.Start,
    ) {
      RemoteText(
        text = data.valueText,
        color = theme.primaryText,
        fontSize = HaType.Title,
        fontWeight = FontWeight.SemiBold,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      RemoteText(
        text = entityName(data.entityId, data.name),
        color = theme.secondaryText,
        fontSize = HaType.Caption,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun animatedGaugeFraction(data: HaGaugeData): RemoteFloat {
  // min / max are template bindings (card.min / card.max), so the fraction is computed in-document.
  val min = cardFloat("min", data.min.toFloat())
  val max = cardFloat("max", data.max.toFloat())
  val animatedValue: RemoteFloat =
    animateRemoteFloat(
      data.value,
      durationSeconds = GaugeAnimationSeconds,
      easing = RcEasing.Standard,
    )
  return normalizedFraction(animatedValue, min, max)
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

private val SeverityNone = HaStatusColors.None
private val SeverityNormal = HaStatusColors.Normal
private val SeverityWarning = HaStatusColors.Warning
private val SeverityCritical = HaStatusColors.Critical

/** The gauge's range line, e.g. `0 – 100 °C`. */
fun gaugeRangeText(min: Double, max: Double, unit: String?): String =
  "${formatRange(min)} – ${formatRange(max)} ${unit.orEmpty()}"

private fun formatRange(d: Double): String =
  if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(d)
