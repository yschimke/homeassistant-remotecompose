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
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxHeight
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.asRemotePaint
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
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
 * The arc sweep is derived from the named `<entityId>.numeric_state`
 * binding (animated through [animateRemoteFloat]) so live value updates
 * tween without re-encoding. Severity bands tint the value arc green /
 * yellow / red (matching HA's `gauge.severity` config); the active
 * band's colour is baked at encode time, so a host re-encodes when the
 * value crosses a band boundary.
 */
@Composable
@RemoteComposable
fun RemoteHaGauge(
    data: HaGaugeData,
    modifier: RemoteModifier = RemoteModifier,
) {
    val theme = haTheme()
    val clickable = data.tapAction.toRemoteAction()
        ?.let { RemoteModifier.clickable(it) } ?: RemoteModifier

    val span = (data.max - data.min).coerceAtLeast(0.0001)

    // Live numeric value from the entity binding, animated so each host
    // update tweens between successive values instead of snapping. When
    // there's no entityId we get a constant RemoteFloat — animation is
    // a no-op since the input never changes.
    val animatedValue: RemoteFloat = animateRemoteFloat(
        data.value,
        durationSeconds = GaugeAnimationSeconds,
        easing = RcEasing.Standard,
    )
    val animatedFraction: RemoteFloat =
        (animatedValue - data.min.toFloat().rf) / span.toFloat().rf
    val animatedSweep: RemoteFloat = animatedFraction * 180f.rf

    val severityColor = when (data.severity) {
        HaGaugeSeverity.None -> Color(0xFF03A9F4)
        HaGaugeSeverity.Normal -> Color(0xFF43A047)
        HaGaugeSeverity.Warning -> Color(0xFFFFA000)
        HaGaugeSeverity.Critical -> Color(0xFFE53935)
    }

    RemoteBox(
        modifier = modifier
            .then(clickable)
            .fillMaxWidth()
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(horizontal = 14.rdp, vertical = 12.rdp),
    ) {
        RemoteColumn(
            modifier = RemoteModifier.fillMaxWidth(),
            verticalArrangement = RemoteArrangement.spacedBy(4.rdp),
            horizontalAlignment = RemoteAlignment.CenterHorizontally,
        ) {
            RemoteCanvas(
                modifier = RemoteModifier.fillMaxWidth().height(72.rdp),
            ) {
                val w = width
                val h = height
                val stroke = 10f.rf
                val pad = (stroke / 2f.rf) + 2f.rf
                val diameter = (h - pad) * 2f.rf
                val left = (w - diameter) / 2f.rf
                val top = h - (diameter / 2f.rf)
                val topLeft = RemoteOffset(left, top)
                val arcSize = RemoteSize(diameter, diameter)

                val track = AndroidPaint().apply {
                    isAntiAlias = true
                    style = AndroidPaint.Style.STROKE
                    strokeWidth = 10f
                    strokeCap = AndroidPaint.Cap.ROUND
                    color = theme.divider.toArgb()
                }.asRemotePaint()
                drawArc(track, 180f.rf, 180f.rf, false, topLeft, arcSize)

                val value = AndroidPaint().apply {
                    isAntiAlias = true
                    style = AndroidPaint.Style.STROKE
                    strokeWidth = 10f
                    strokeCap = AndroidPaint.Cap.ROUND
                    color = severityColor.toArgb()
                }.asRemotePaint()
                drawArc(value, 180f.rf, animatedSweep, false, topLeft, arcSize)
            }

            RemoteText(
                text = data.valueText,
                color = theme.primaryText.rc,
                fontSize = 22.rsp,
                fontWeight = FontWeight.SemiBold,
                style = RemoteTextStyle.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RemoteText(
                text = data.name.rs,
                color = theme.secondaryText.rc,
                fontSize = 13.rsp,
                style = RemoteTextStyle.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (data.unit != null) {
                RemoteText(
                    text = "${formatRange(data.min)} – ${formatRange(data.max)} ${data.unit}".rs,
                    color = theme.secondaryText.rc,
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
 * Compact Fixed-mode gauge variant. The half-arc grows to fill the
 * card's intrinsic dimensions (capped at the smaller of `width / 2`
 * and `height` so it stays a true half-circle), and the live value
 * is overlaid centered inside the arc's open interior. Use for
 * square / tall launcher widgets where the wrap-mode column-stack is
 * wasteful.
 */
@Composable
@RemoteComposable
fun RemoteHaGaugeCompact(
    data: HaGaugeData,
    modifier: RemoteModifier = RemoteModifier,
) {
    val theme = haTheme()
    val animatedSweep = animatedGaugeSweep(data)
    val severityColor = severityColorFor(data.severity)
    val clickable =
        data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier

    RemoteBox(
        modifier =
            modifier
                .then(clickable)
                .clip(RemoteRoundedCornerShape(12.rdp))
                .background(theme.cardBackground.rc)
                .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp)),
        contentAlignment = RemoteAlignment.Center,
    ) {
        // Arc fills the cell; its diameter follows the smaller axis so
        // the half-circle stays proportional. Anchored to the bottom
        // so the open side faces downward (matching the HA reference).
        RemoteCanvas(modifier = RemoteModifier.fillMaxSize().padding(6.rdp)) {
            val w = width
            val h = height
            val stroke = 8f.rf
            val pad = stroke / 2f.rf
            // Half-circle diameter capped by both axes.
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
                        strokeWidth = 8f
                        strokeCap = AndroidPaint.Cap.ROUND
                        color = theme.divider.toArgb()
                    }
                    .asRemotePaint()
            drawArc(track, 180f.rf, 180f.rf, false, topLeft, arcSize)

            val value =
                AndroidPaint()
                    .apply {
                        isAntiAlias = true
                        style = AndroidPaint.Style.STROKE
                        strokeWidth = 8f
                        strokeCap = AndroidPaint.Cap.ROUND
                        color = severityColor.toArgb()
                    }
                    .asRemotePaint()
            drawArc(value, 180f.rf, animatedSweep, false, topLeft, arcSize)
        }
        // Value text overlaid inside the arc — Box centers by default,
        // which lands the text in the half-arc's interior.
        RemoteText(
            text = data.valueText,
            color = theme.primaryText.rc,
            fontSize = 16.rsp,
            fontWeight = FontWeight.SemiBold,
            style = RemoteTextStyle.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = RemoteModifier.padding(bottom = 8.rdp),
        )
    }
}

/**
 * Wide-thin Fixed-mode gauge variant. Half-arc on the left at square
 * aspect, value + name + range stacked on the right. Targets cells
 * like `200×60` Wear chips and `2×1` launcher widgets where the
 * vertical stack of [RemoteHaGaugeCompact] would crowd the arc.
 */
@Composable
@RemoteComposable
fun RemoteHaGaugeWide(
    data: HaGaugeData,
    modifier: RemoteModifier = RemoteModifier,
) {
    val theme = haTheme()
    val animatedSweep = animatedGaugeSweep(data)
    val severityColor = severityColorFor(data.severity)
    val clickable =
        data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier

    RemoteRow(
        modifier =
            modifier
                .then(clickable)
                .clip(RemoteRoundedCornerShape(12.rdp))
                .background(theme.cardBackground.rc)
                .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
                .padding(horizontal = 8.rdp, vertical = 6.rdp),
        verticalAlignment = RemoteAlignment.CenterVertically,
        horizontalArrangement = RemoteArrangement.spacedBy(8.rdp),
    ) {
        // Arc canvas: square aspect, height-bounded.
        RemoteCanvas(modifier = RemoteModifier.fillMaxHeight().width(64.rdp)) {
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
                        color = theme.divider.toArgb()
                    }
                    .asRemotePaint()
            drawArc(track, 180f.rf, 180f.rf, false, topLeft, arcSize)

            val value =
                AndroidPaint()
                    .apply {
                        isAntiAlias = true
                        style = AndroidPaint.Style.STROKE
                        strokeWidth = 6f
                        strokeCap = AndroidPaint.Cap.ROUND
                        color = severityColor.toArgb()
                    }
                    .asRemotePaint()
            drawArc(value, 180f.rf, animatedSweep, false, topLeft, arcSize)
        }
        RemoteColumn(
            verticalArrangement = RemoteArrangement.Center,
            horizontalAlignment = RemoteAlignment.Start,
        ) {
            RemoteText(
                text = data.valueText,
                color = theme.primaryText.rc,
                fontSize = 16.rsp,
                fontWeight = FontWeight.SemiBold,
                style = RemoteTextStyle.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RemoteText(
                text = data.name.rs,
                color = theme.secondaryText.rc,
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
    val span = (data.max - data.min).coerceAtLeast(0.0001)
    val animatedValue: RemoteFloat =
        animateRemoteFloat(
            data.value,
            durationSeconds = GaugeAnimationSeconds,
            easing = RcEasing.Standard,
        )
    val animatedFraction: RemoteFloat =
        (animatedValue - data.min.toFloat().rf) / span.toFloat().rf
    return animatedFraction * 180f.rf
}

private fun severityColorFor(severity: HaGaugeSeverity): Color =
    when (severity) {
        HaGaugeSeverity.None -> Color(0xFF03A9F4)
        HaGaugeSeverity.Normal -> Color(0xFF43A047)
        HaGaugeSeverity.Warning -> Color(0xFFFFA000)
        HaGaugeSeverity.Critical -> Color(0xFFE53935)
    }

private fun formatRange(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(d)

private fun Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255f).toInt(),
        (red * 255f).toInt(),
        (green * 255f).toInt(),
        (blue * 255f).toInt(),
    )
