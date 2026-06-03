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
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxHeight
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * HA `weather-forecast` card. Top row shows the current condition icon
 * + summary + headline temperature; an optional strip of forecast days follows below.
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
  fillHeight: Boolean = false,
  showExtras: Boolean = true,
) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 12.rdp)
  ) {
    RemoteColumn(
      modifier = if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier.fillMaxWidth(),
      verticalArrangement = RemoteArrangement.spacedBy(10.rdp),
    ) {
      CurrentRow(data, theme)
      if (data.days.isNotEmpty()) {
        if (fillHeight) {
          // Expanded (Fixed mode): the forecast strip claims the
          // remaining height and its day columns spread + enlarge
          // to fill it, so a tall cell (e.g. 6×3) is used rather
          // than left half-blank — Principle 7/8, "earn the canvas
          // / no dead space". The fill is done *inside* the strip
          // (weighted day columns + larger glyphs), not by centring
          // a compact strip in a wrapper.
          ForecastStrip(
            data.days,
            theme,
            modifier = RemoteModifier.fillMaxWidth().weight(1f),
            fill = true,
          )
        } else {
          ForecastStrip(data.days, theme)
        }
      }
      // Extra current-conditions read-outs claim the bottom of the roomy
      // variants. Folding them in here keeps the "everything about the
      // weather in one place" identity rather than spilling humidity /
      // wind / pressure into separate sibling cards.
      if (showExtras && data.extras.isNotEmpty()) {
        ExtraInfoRow(data.extras, theme)
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
            text = data.supportingLine.rs,
            color = theme.secondaryText.rc,
            fontSize = 12.rsp,
            style = RemoteTextStyle.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        } else {
          RemoteText(
            text = data.name.rs,
            color = theme.secondaryText.rc,
            fontSize = 12.rsp,
            style = RemoteTextStyle.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
    RemoteColumn(horizontalAlignment = RemoteAlignment.End) {
      RemoteText(
        text = LiveValues.attribute(data.entityId, "temperature_label", data.temperature),
        color = theme.primaryText.rc,
        fontSize = 28.rsp,
        fontWeight = FontWeight.Light,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (data.highLow != null) {
        RemoteText(
          text = data.highLow.rs,
          color = theme.secondaryText.rc,
          fontSize = 13.rsp,
          style = RemoteTextStyle.Default,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/**
 * Bottom strip of supplementary current-conditions read-outs — each a small inset chip with an
 * icon, value, and label. Mirrors the forecast strip's spread-and-centre column layout so the two
 * rows read as one coherent block.
 */
@Composable
private fun ExtraInfoRow(extras: List<HaWeatherExtra>, theme: HaTheme) {
  RemoteRow(
    modifier = RemoteModifier.fillMaxWidth(),
    horizontalArrangement = RemoteArrangement.spacedBy(8.rdp),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    extras.forEach { extra ->
      RemoteColumn(
        modifier =
          RemoteModifier.weight(1f)
            .clip(RemoteRoundedCornerShape(10.rdp))
            .background(theme.divider.rc)
            .padding(horizontal = 4.rdp, vertical = 8.rdp),
        horizontalAlignment = RemoteAlignment.CenterHorizontally,
        verticalArrangement = RemoteArrangement.spacedBy(3.rdp),
      ) {
        RemoteIcon(
          imageVector = extra.icon,
          contentDescription = extra.label.rs,
          modifier = RemoteModifier.size(16.rdp),
          tint = theme.secondaryText.rc,
        )
        RemoteText(
          text = extra.value.rs,
          color = theme.primaryText.rc,
          fontSize = 13.rsp,
          fontWeight = FontWeight.Medium,
          style = RemoteTextStyle.Default,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        RemoteText(
          text = extra.label.rs,
          color = theme.secondaryText.rc,
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
 * Wide-thin Fixed-mode weather variant — icon + condition stacked on the left, big temperature
 * right. Targets short widget cells (Wear S/L) where the full card's forecast strip won't fit. The
 * condition text ellipsises rather than wrapping so the row stays one line.
 */
@Composable
@RemoteComposable
fun RemoteHaWeatherForecastWide(
  data: HaWeatherForecastData,
  modifier: RemoteModifier = RemoteModifier,
) {
  val theme = haTheme()
  RemoteRow(
    modifier =
      modifier
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 10.rdp, vertical = 8.rdp),
    verticalAlignment = RemoteAlignment.CenterVertically,
    horizontalArrangement = RemoteArrangement.spacedBy(8.rdp),
  ) {
    RemoteIcon(
      imageVector = data.icon,
      contentDescription = data.condition,
      modifier = RemoteModifier.size(28.rdp),
      tint = theme.primaryText.rc,
    )
    RemoteColumn(
      modifier = RemoteModifier.weight(1f),
      verticalArrangement = RemoteArrangement.Center,
    ) {
      RemoteText(
        text = data.condition,
        color = theme.primaryText.rc,
        fontSize = 13.rsp,
        fontWeight = FontWeight.Medium,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      RemoteText(
        text = (data.supportingLine ?: data.name).rs,
        color = theme.secondaryText.rc,
        fontSize = 11.rsp,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    RemoteText(
      text = LiveValues.attribute(data.entityId, "temperature_label", data.temperature),
      color = theme.primaryText.rc,
      fontSize = 20.rsp,
      fontWeight = FontWeight.Light,
      style = RemoteTextStyle.Default,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun ForecastStrip(
  days: List<HaWeatherDay>,
  theme: HaTheme,
  modifier: RemoteModifier = RemoteModifier,
  fill: Boolean = false,
) {
  RemoteRow(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = RemoteArrangement.SpaceBetween,
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    days.forEach { day ->
      RemoteColumn(
        modifier =
          if (fill) RemoteModifier.weight(1f).fillMaxHeight() else RemoteModifier.weight(1f),
        horizontalAlignment = RemoteAlignment.CenterHorizontally,
        verticalArrangement =
          if (fill) RemoteArrangement.SpaceEvenly else RemoteArrangement.spacedBy(2.rdp),
      ) {
        RemoteText(
          text = day.label.rs,
          color = theme.secondaryText.rc,
          fontSize = if (fill) 13.rsp else 11.rsp,
          style = RemoteTextStyle.Default,
          maxLines = 1,
        )
        RemoteIcon(
          imageVector = day.icon,
          contentDescription = day.label.rs,
          modifier = RemoteModifier.size(if (fill) 32.rdp else 20.rdp),
          tint = theme.primaryText.rc,
        )
        RemoteText(
          text = day.high.rs,
          color = theme.primaryText.rc,
          fontSize = if (fill) 15.rsp else 12.rsp,
          fontWeight = FontWeight.Medium,
          style = RemoteTextStyle.Default,
          maxLines = 1,
        )
        RemoteText(
          text = day.low.rs,
          color = theme.secondaryText.rc,
          fontSize = if (fill) 13.rsp else 11.rsp,
          style = RemoteTextStyle.Default,
          maxLines = 1,
        )
      }
    }
  }
}
