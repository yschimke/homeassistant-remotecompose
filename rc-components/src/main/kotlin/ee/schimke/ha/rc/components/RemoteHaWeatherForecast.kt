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
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * HA `weather-forecast` card. Top row shows the current condition icon
 * + summary + headline temperature; an optional strip of forecast days
 * follows below.
 *
 * ```
 *   ┌───────────────────────────────────────────────┐
 *   │  ☀  Partly cloudy                  21°       │
 *   │     Feels like 19°                            │
 *   │  ─────────────────────────────────────────    │
 *   │   Mon   Tue   Wed   Thu   Fri                 │
 *   │   ☀    ⛅    ☁    🌧    ⛈                     │
 *   │  21/12 19/11 17/10 14/9  13/8                 │
 *   └───────────────────────────────────────────────┘
 * ```
 */
@Composable
@RemoteComposable
fun RemoteHaWeatherForecast(
    data: HaWeatherForecastData,
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
        RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(10.rdp)) {
            CurrentRow(data, theme)
            if (data.days.isNotEmpty()) {
                ForecastStrip(data.days, theme)
            }
        }
    }
}

@Composable
private fun CurrentRow(data: HaWeatherForecastData, theme: HaTheme) {
    RemoteRow(
        modifier = RemoteModifier.fillMaxWidth(),
        verticalAlignment = RemoteAlignment.CenterVertically,
        horizontalArrangement = RemoteArrangement.SpaceBetween,
    ) {
        RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
            RemoteIcon(
                imageVector = data.icon,
                contentDescription = data.condition,
                modifier = RemoteModifier.size(36.rdp),
                tint = theme.primaryText.rc,
            )
            RemoteColumn(modifier = RemoteModifier.padding(start = 10.rdp)) {
                RemoteText(
                    text = data.condition,
                    color = theme.primaryText.rc,
                    fontSize = 16.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (data.supportingLine != null) {
                    RemoteText(
                        text = data.supportingLine,
                        color = theme.secondaryText.rc,
                        fontSize = 12.rsp,
                        style = RemoteTextStyle.Default,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    RemoteText(
                        text = data.name,
                        color = theme.secondaryText.rc,
                        fontSize = 12.rsp,
                        style = RemoteTextStyle.Default,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        RemoteText(
            text = data.temperature,
            color = theme.primaryText.rc,
            fontSize = 28.rsp,
            fontWeight = FontWeight.Light,
            style = RemoteTextStyle.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ForecastStrip(days: List<HaWeatherDay>, theme: HaTheme) {
    RemoteRow(
        modifier = RemoteModifier.fillMaxWidth(),
        horizontalArrangement = RemoteArrangement.SpaceBetween,
    ) {
        days.forEach { day ->
            RemoteColumn(
                modifier = RemoteModifier.weight(1f),
                horizontalAlignment = RemoteAlignment.CenterHorizontally,
                verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
            ) {
                RemoteText(
                    text = day.label,
                    color = theme.secondaryText.rc,
                    fontSize = 11.rsp,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                )
                RemoteIcon(
                    imageVector = day.icon,
                    contentDescription = day.label,
                    modifier = RemoteModifier.size(20.rdp),
                    tint = theme.primaryText.rc,
                )
                RemoteText(
                    text = day.high,
                    color = theme.primaryText.rc,
                    fontSize = 12.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                )
                RemoteText(
                    text = day.low,
                    color = theme.secondaryText.rc,
                    fontSize = 11.rsp,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                )
            }
        }
    }
}
