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
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
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
 * The arc sweep representing `(value - min) / (max - min)` is computed
 * at capture time; the host re-encodes when the underlying entity
 * changes. Severity bands tint the value arc green / yellow / red
 * (matching HA's `gauge.severity` config). Alpha08 doesn't expose a
 * RemoteFloat numeric binding to animate the sweep without re-encode.
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
    val fraction = ((data.value - data.min) / span).coerceIn(0.0, 1.0).toFloat()
    val sweep = (180f * fraction)

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
                val arcW = w - pad * 2f.rf
                val arcH = (h - pad) * 2f.rf
                val topLeft = RemoteOffset(pad, pad)
                val arcSize = RemoteSize(arcW, arcH)

                val track = AndroidPaint().apply {
                    isAntiAlias = true
                    style = AndroidPaint.Style.STROKE
                    strokeWidth = 10f
                    strokeCap = AndroidPaint.Cap.ROUND
                    color = theme.divider.toArgb()
                }.asRemotePaint()
                drawArc(track, 180f.rf, 180f.rf, false, topLeft, arcSize)

                if (sweep > 0.5f) {
                    val value = AndroidPaint().apply {
                        isAntiAlias = true
                        style = AndroidPaint.Style.STROKE
                        strokeWidth = 10f
                        strokeCap = AndroidPaint.Cap.ROUND
                        color = severityColor.toArgb()
                    }.asRemotePaint()
                    drawArc(value, 180f.rf, sweep.rf, false, topLeft, arcSize)
                }
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
                text = data.name,
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

private fun formatRange(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(d)

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt(),
    (red * 255f).toInt(),
    (green * 255f).toInt(),
    (blue * 255f).toInt(),
)
