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
 * `statistics-graph` card. Three visual modes:
 *
 *  - line (default) — one sparkline per row, identical to history-graph.
 *  - bar — one bar per bucket per row, drawn as filled rects.
 *  - stacked bar (only when [HaStatisticsGraphData.stacked]) — a single
 *    combined canvas where each bucket is the sum of all rows, with
 *    each row contributing a coloured segment. Rows whose lengths
 *    differ are aligned to the longest series; missing samples count
 *    as zero.
 *
 * Stacking only makes sense for non-negative sums (e.g. power per
 * room); negative values render below the baseline at their own offset
 * which matches HA's behaviour.
 */
@Composable
@RemoteComposable
fun RemoteHaStatisticsGraph(
    data: HaStatisticsGraphData,
    modifier: RemoteModifier = RemoteModifier,
) {
    if (data.chartType != "bar") {
        RemoteHaHistoryGraph(
            HaHistoryGraphData(
                title = data.title,
                rangeLabel = data.rangeLabel,
                rows = data.rows,
            ),
            modifier = modifier,
        )
        return
    }

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
            } else if (data.stacked) {
                StackedBars(data.rows, theme)
                data.rows.forEach { row -> Legend(row, theme) }
            } else {
                data.rows.forEach { row -> BarRow(row, theme) }
            }
        }
    }
}

@Composable
private fun BarRow(row: HaHistoryGraphRow, theme: HaTheme) {
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
    if (row.points.isEmpty()) {
        RemoteText(
            text = "No samples".rs,
            color = theme.secondaryText.rc,
            fontSize = 11.rsp,
            style = RemoteTextStyle.Default,
        )
    } else {
        Bars(row.points, row.accent, theme)
    }
}

@Composable
private fun Legend(row: HaHistoryGraphRow, theme: HaTheme) {
    RemoteRow(
        modifier = RemoteModifier.fillMaxWidth(),
        verticalAlignment = RemoteAlignment.CenterVertically,
        horizontalArrangement = RemoteArrangement.SpaceBetween,
    ) {
        RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
            RemoteBox(
                modifier = RemoteModifier
                    .height(10.rdp)
                    .clip(RemoteRoundedCornerShape(2.rdp))
                    .background(row.accent.rc)
                    .padding(horizontal = 5.rdp),
            ) {}
            RemoteText(
                text = row.name,
                color = theme.primaryText.rc,
                fontSize = 11.rsp,
                style = RemoteTextStyle.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = RemoteModifier.padding(start = 6.rdp),
            )
        }
        RemoteText(
            text = row.summary,
            color = theme.secondaryText.rc,
            fontSize = 11.rsp,
            style = RemoteTextStyle.Default,
            maxLines = 1,
        )
    }
}

@Composable
private fun Bars(points: List<Float>, accent: Color, theme: HaTheme) {
    val minP = points.min()
    val maxP = points.max()
    // Anchor bars to zero when all values are non-negative; otherwise
    // anchor at the row minimum so negative-only series still fill the
    // canvas instead of collapsing to a single line.
    val base = if (minP >= 0f) 0f else minP
    val span = (maxP - base).takeIf { it > 0f } ?: 1f
    val n = points.size
    RemoteCanvas(modifier = RemoteModifier.fillMaxWidth().height(28.rdp)) {
        val w = width
        val h = height
        val padX = 2f.rf
        val padY = 3f.rf
        val drawW = w - padX * 2f.rf
        val drawH = h - padY * 2f.rf

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

        val fill = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.FILL
            color = accent.toArgb()
        }.asRemotePaint()

        val slot = drawW / n.toFloat().rf
        val barWidth = slot * 0.7f.rf
        val gap = (slot - barWidth) * 0.5f.rf
        for (i in 0 until n) {
            val x0 = padX + slot * i.toFloat().rf + gap
            val barHeight = drawH * ((points[i] - base) / span).rf
            val y0 = padY + drawH - barHeight
            drawRect(fill, RemoteOffset(x0, y0), RemoteSize(barWidth, barHeight))
        }
    }
}

@Composable
private fun StackedBars(rows: List<HaHistoryGraphRow>, theme: HaTheme) {
    val n = rows.maxOf { it.points.size }
    val totals = FloatArray(n)
    for (i in 0 until n) {
        totals[i] = rows.sumOf { row ->
            (row.points.getOrNull(i) ?: 0f).coerceAtLeast(0f).toDouble()
        }.toFloat()
    }
    val maxTotal = totals.maxOrNull()?.takeIf { it > 0f } ?: 1f

    RemoteCanvas(modifier = RemoteModifier.fillMaxWidth().height(64.rdp)) {
        val w = width
        val h = height
        val padX = 2f.rf
        val padY = 3f.rf
        val drawW = w - padX * 2f.rf
        val drawH = h - padY * 2f.rf

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

        val slot = drawW / n.toFloat().rf
        val barWidth = slot * 0.75f.rf
        val gap = (slot - barWidth) * 0.5f.rf

        for (i in 0 until n) {
            val x0 = padX + slot * i.toFloat().rf + gap
            var cumulative = 0f
            for (row in rows) {
                val v = (row.points.getOrNull(i) ?: 0f).coerceAtLeast(0f)
                if (v <= 0f) continue
                val segTop = padY + drawH * (1f - (cumulative + v) / maxTotal).rf
                val segHeight = drawH * (v / maxTotal).rf
                val fill = AndroidPaint().apply {
                    isAntiAlias = true
                    style = AndroidPaint.Style.FILL
                    color = row.accent.toArgb()
                }.asRemotePaint()
                drawRect(fill, RemoteOffset(x0, segTop), RemoteSize(barWidth, segHeight))
                cumulative += v
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
