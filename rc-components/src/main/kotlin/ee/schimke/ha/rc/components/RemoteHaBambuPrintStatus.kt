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
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.asRemotePaint
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * `custom:ha-bambulab-print_status-card` — printer hero tile with a
 * progress ring + the key sensor readouts (stage, layer, remaining,
 * temperatures).
 *
 * ```
 *   ┌─────────────────────────────────────────────┐
 *   │  P2S                                        │
 *   │   ╭──╮   34 %                               │
 *   │   │  │   inspecting first layer             │
 *   │   ╰──╯   Layer 12 / 240    1 h 22 m left    │
 *   │          Nozzle 220 °C    Bed 60 °C         │
 *   └─────────────────────────────────────────────┘
 * ```
 *
 * Progress ring is captured statically at the supplied fraction; alpha08
 * doesn't expose a numeric `RemoteFloat` binding, so live updates
 * re-encode (same constraint as gauge / history-graph).
 */
@Composable
@RemoteComposable
fun RemoteHaBambuPrintStatus(
    data: HaBambuPrintStatusData,
    modifier: RemoteModifier = RemoteModifier,
) {
    val theme = haTheme()
    RemoteBox(
        modifier = modifier
            .fillMaxWidth()
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(horizontal = 14.rdp, vertical = 12.rdp),
    ) {
        RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
            RemoteText(
                text = data.printerName,
                color = theme.secondaryText.rc,
                fontSize = 11.rsp,
                style = RemoteTextStyle.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RemoteRow(
                modifier = RemoteModifier.fillMaxWidth(),
                verticalAlignment = RemoteAlignment.CenterVertically,
            ) {
                ProgressRing(data.progressFraction, data.accent, theme)
                RemoteColumn(
                    modifier = RemoteModifier.padding(start = 14.rdp),
                    verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
                ) {
                    RemoteText(
                        text = data.progressLabel,
                        color = theme.primaryText.rc,
                        fontSize = 22.rsp,
                        fontWeight = FontWeight.SemiBold,
                        style = RemoteTextStyle.Default,
                        maxLines = 1,
                    )
                    RemoteText(
                        text = data.stage,
                        color = theme.secondaryText.rc,
                        fontSize = 13.rsp,
                        style = RemoteTextStyle.Default,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            data.layerLine?.let {
                RemoteText(
                    text = it,
                    color = theme.secondaryText.rc,
                    fontSize = 12.rsp,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                )
            }
            data.remainingLine?.let {
                RemoteText(
                    text = it,
                    color = theme.secondaryText.rc,
                    fontSize = 12.rsp,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                )
            }
            if (data.nozzleLine != null || data.bedLine != null) {
                RemoteRow(
                    modifier = RemoteModifier.fillMaxWidth(),
                    horizontalArrangement = RemoteArrangement.spacedBy(16.rdp),
                ) {
                    data.nozzleLine?.let {
                        RemoteText(
                            text = it,
                            color = theme.secondaryText.rc,
                            fontSize = 12.rsp,
                            style = RemoteTextStyle.Default,
                            maxLines = 1,
                        )
                    }
                    data.bedLine?.let {
                        RemoteText(
                            text = it,
                            color = theme.secondaryText.rc,
                            fontSize = 12.rsp,
                            style = RemoteTextStyle.Default,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressRing(fraction: Float, accent: Color, theme: HaTheme) {
    val sweep = (fraction.coerceIn(0f, 1f) * 360f)
    RemoteBox(modifier = RemoteModifier.size(64.rdp)) {
        RemoteCanvas(modifier = RemoteModifier.size(64.rdp)) {
            val w = width
            val h = height
            val stroke = 8f.rf
            val pad = stroke / 2f.rf + 1f.rf
            val topLeft = RemoteOffset(pad, pad)
            val arcSize = RemoteSize(w - pad * 2f.rf, h - pad * 2f.rf)

            val track = AndroidPaint().apply {
                isAntiAlias = true
                style = AndroidPaint.Style.STROKE
                strokeWidth = 8f
                strokeCap = AndroidPaint.Cap.ROUND
                color = theme.divider.toArgb()
            }.asRemotePaint()
            drawArc(track, 0f.rf, 360f.rf, false, topLeft, arcSize)

            if (sweep > 0.5f) {
                val progress = AndroidPaint().apply {
                    isAntiAlias = true
                    style = AndroidPaint.Style.STROKE
                    strokeWidth = 8f
                    strokeCap = AndroidPaint.Cap.ROUND
                    color = accent.toArgb()
                }.asRemotePaint()
                // Start at 12 o'clock (Android draws 0° at 3 o'clock).
                drawArc(progress, (-90f).rf, sweep.rf, false, topLeft, arcSize)
            }
        }
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt(),
    (red * 255f).toInt(),
    (green * 255f).toInt(),
    (blue * 255f).toInt(),
)
