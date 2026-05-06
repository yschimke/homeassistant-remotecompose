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
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
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
 * HA `history-graph` card — title, range label, then one sparkline row
 * per entity.
 *
 * ```
 *   ┌─────────────────────────────────────────────┐
 *   │  Temperature                                │
 *   │  Last 24h                                   │
 *   │  Outside        ╱╲╱╲╱╲╱╲╱╲╱╲╱╲   8.2 °C    │
 *   │  Upstairs       ─────~~~~~~──   22.2 °C    │
 *   └─────────────────────────────────────────────┘
 * ```
 *
 * Sparkline is captured from numeric `HistoryPoint`s. Sample values get
 * normalised into the row's drawable rect at capture time; alpha08
 * doesn't expose a numeric `RemoteFloat` binding for live updates, so a
 * host that wants a moving line re-encodes when history changes.
 */
@Composable
@RemoteComposable
fun RemoteHaHistoryGraph(
    data: HaHistoryGraphData,
    modifier: RemoteModifier = RemoteModifier,
) {
    val theme = haTheme()
    RemoteBox(
        modifier = modifier
            .fillMaxWidth()
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(horizontal = 14.rdp, vertical = 10.rdp),
    ) {
        RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(6.rdp)) {
            if (data.title != null) {
                RemoteText(
                    text = data.title,
                    color = theme.primaryText.rc,
                    fontSize = 15.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RemoteText(
                text = data.rangeLabel,
                color = theme.secondaryText.rc,
                fontSize = 11.rsp,
                style = RemoteTextStyle.Default,
            )
            if (data.rows.isEmpty()) {
                RemoteText(
                    text = "No entities".rs,
                    color = theme.secondaryText.rc,
                    fontSize = 13.rsp,
                    style = RemoteTextStyle.Default,
                )
            } else {
                data.rows.forEach { row -> Row(row, theme) }
            }
        }
    }
}

@Composable
private fun Row(row: HaHistoryGraphRow, theme: HaTheme) {
    RemoteRow(
        modifier = RemoteModifier.fillMaxWidth(),
        verticalAlignment = RemoteAlignment.CenterVertically,
        horizontalArrangement = RemoteArrangement.SpaceBetween,
    ) {
        RemoteText(
            text = row.name,
            color = theme.primaryText.rc,
            fontSize = 12.rsp,
            fontWeight = FontWeight.Medium,
            style = RemoteTextStyle.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RemoteText(
            text = row.summary,
            color = theme.secondaryText.rc,
            fontSize = 11.rsp,
            style = RemoteTextStyle.Default,
            maxLines = 1,
        )
    }
    if (row.points.size >= 2) {
        Sparkline(row.points, row.accent, theme)
    } else {
        RemoteText(
            text = "No samples".rs,
            color = theme.secondaryText.rc,
            fontSize = 11.rsp,
            style = RemoteTextStyle.Default,
        )
    }
}

@Composable
private fun Sparkline(points: List<Float>, accent: Color, theme: HaTheme) {
    val minP = points.min()
    val maxP = points.max()
    val span = (maxP - minP).takeIf { it > 0f } ?: 1f
    RemoteCanvas(modifier = RemoteModifier.fillMaxWidth().height(28.rdp)) {
        val w = width
        val h = height
        val padX = 2f.rf
        val padY = 3f.rf
        val drawW = w - padX * 2f.rf
        val drawH = h - padY * 2f.rf

        // Faint baseline so an empty/flat row still shows the row's
        // vertical extent.
        val baseline = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = 1f
            color = theme.divider.toArgb()
        }.asRemotePaint()
        drawLine(
            baseline,
            RemoteOffset(padX, padY + drawH),
            RemoteOffset(padX + drawW, padY + drawH),
        )

        val stroke = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = 2f
            strokeCap = AndroidPaint.Cap.ROUND
            strokeJoin = AndroidPaint.Join.ROUND
            color = accent.toArgb()
        }.asRemotePaint()

        val n = points.size
        for (i in 0 until n - 1) {
            val x0 = padX + drawW * (i.toFloat() / (n - 1).toFloat()).rf
            val x1 = padX + drawW * ((i + 1).toFloat() / (n - 1).toFloat()).rf
            val y0 = padY + drawH * (1f - (points[i] - minP) / span).rf
            val y1 = padY + drawH * (1f - (points[i + 1] - minP) / span).rf
            drawLine(stroke, RemoteOffset(x0, y0), RemoteOffset(x1, y1))
        }
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt(),
    (red * 255f).toInt(),
    (green * 255f).toInt(),
    (blue * 255f).toInt(),
)
