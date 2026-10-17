@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.capture.toRemoteImageVector
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
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
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
private fun CurrentRow(data: HaWeatherForecastData, theme: RemoteHaTheme) {
  RemoteRow(
    modifier = RemoteModifier.fillMaxWidth(),
    verticalAlignment = RemoteAlignment.CenterVertically,
    horizontalArrangement = RemoteArrangement.SpaceBetween,
  ) {
    RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
      RemoteIcon(
        imageVector = data.icon.toRemoteImageVector(),
        contentDescription = data.condition,
        modifier = RemoteModifier.size(36.rdp),
        tint = theme.primaryText,
      )
      RemoteColumn(modifier = RemoteModifier.padding(start = 10.rdp)) {
        RemoteText(
          text = data.condition,
          color = theme.primaryText,
          fontSize = HaType.Title,
          fontWeight = FontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (data.supportingLine != null) {
          RemoteText(
            text = data.supportingLine.rs,
            color = theme.secondaryText,
            fontSize = HaType.Label,
            style = haTextStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        } else {
          RemoteText(
            text = entityName(data.entityId, data.name),
            color = theme.secondaryText,
            fontSize = HaType.Label,
            style = haTextStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
    RemoteColumn(horizontalAlignment = RemoteAlignment.End) {
      RemoteText(
        text = LiveValues.attribute(data.entityId, "temperature_label", data.temperature),
        color = theme.primaryText,
        fontSize = HaType.ValueLarge,
        fontWeight = haValueWeight(),
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (data.highLow != null) {
        RemoteText(
          text = cardText("high_low", data.highLow),
          color = theme.secondaryText,
          fontSize = HaType.Body,
          style = haTextStyle(),
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
 *
 * The chips share the row's width equally, separated by fixed-width spacers rather than a
 * `spacedBy` arrangement: the RemoteCompose row divides the full width among weighted children
 * without subtracting `spacedBy` gaps (fixed-size children it does subtract), so with four chips
 * and three 8 dp gaps the last chip ran 24 dp past the card edge and was clipped. With spacers the
 * chips sum to exactly the row's width, and a value too long for its share ellipsises instead of
 * pushing the row wider. The gap and the chips' side padding are kept tight so a four-chip row
 * still fits a value like "1012.7 hPa" on a typical phone.
 */
@Composable
private fun ExtraInfoRow(extras: List<HaWeatherExtra>, theme: RemoteHaTheme) {
  RemoteRow(
    modifier = RemoteModifier.fillMaxWidth(),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    extras.forEachIndexed { i, extra ->
      if (i > 0) RemoteBox(modifier = RemoteModifier.width(ChipGapDp.rdp))
      ExtraChip(i, extra, theme, RemoteModifier.weight(1f))
    }
  }
}

/** Gap between two condition chips, in dp. */
private const val ChipGapDp = 6

@Composable
private fun ExtraChip(
  i: Int,
  extra: HaWeatherExtra,
  theme: RemoteHaTheme,
  modifier: RemoteModifier,
) {
  RemoteColumn(
    modifier =
      modifier
        .clip(haControlShape(10))
        .background(theme.divider)
        .padding(horizontal = 3.rdp, vertical = 8.rdp),
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
    verticalArrangement = RemoteArrangement.spacedBy(3.rdp),
  ) {
    RemoteIcon(
      imageVector = extra.icon.toRemoteImageVector(),
      contentDescription = extra.label.rs,
      modifier = RemoteModifier.size(16.rdp),
      tint = theme.secondaryText,
    )
    RemoteText(
      text = cardText("extra.$i", extra.value),
      color = theme.primaryText,
      fontSize = HaType.Body,
      fontWeight = FontWeight.Medium,
      style = haTextStyle(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    RemoteText(
      text = extra.label.rs,
      color = theme.secondaryText,
      fontSize = HaType.Micro,
      style = haTextStyle(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
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
  ) {
    RemoteIcon(
      imageVector = data.icon.toRemoteImageVector(),
      contentDescription = data.condition,
      modifier = RemoteModifier.size(28.rdp),
      tint = theme.primaryText,
    )
    // The 8 dp gaps either side of the text are its own padding, not a `spacedBy` arrangement:
    // the RemoteCompose row doesn't subtract `spacedBy` gaps from the weighted share, which pushed
    // the temperature 16 dp past the slot's edge (clipped on Wear and the 4×1 widget).
    RemoteColumn(
      modifier = RemoteModifier.weight(1f).padding(horizontal = 8.rdp),
      verticalArrangement = RemoteArrangement.Center,
    ) {
      RemoteText(
        text = data.condition,
        color = theme.primaryText,
        fontSize = HaType.Body,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      RemoteText(
        text = data.supportingLine?.rs ?: entityName(data.entityId, data.name),
        color = theme.secondaryText,
        fontSize = HaType.Caption,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    RemoteText(
      text = LiveValues.attribute(data.entityId, "temperature_label", data.temperature),
      color = theme.primaryText,
      fontSize = HaType.ValueSmall,
      fontWeight = haValueWeight(),
      style = haTextStyle(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun ForecastStrip(
  days: List<HaWeatherDay>,
  theme: RemoteHaTheme,
  modifier: RemoteModifier = RemoteModifier,
  fill: Boolean = false,
) {
  RemoteRow(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = RemoteArrangement.SpaceBetween,
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    days.forEachIndexed { i, day ->
      val label = cardText("day.$i.label", day.label)
      RemoteColumn(
        modifier =
          if (fill) RemoteModifier.weight(1f).fillMaxHeight() else RemoteModifier.weight(1f),
        horizontalAlignment = RemoteAlignment.CenterHorizontally,
        verticalArrangement =
          if (fill) RemoteArrangement.SpaceEvenly else RemoteArrangement.spacedBy(2.rdp),
      ) {
        RemoteText(
          text = label,
          color = theme.secondaryText,
          fontSize = if (fill) HaType.Body else HaType.Caption,
          style = haTextStyle(),
          maxLines = 1,
        )
        RemoteIcon(
          imageVector = day.icon.toRemoteImageVector(),
          contentDescription = label,
          modifier = RemoteModifier.size(if (fill) 32.rdp else 20.rdp),
          tint = theme.primaryText,
        )
        RemoteText(
          text = cardText("day.$i.high", day.high),
          color = theme.primaryText,
          fontSize = if (fill) HaType.BodyLarge else HaType.Label,
          fontWeight = FontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
        )
        RemoteText(
          text = cardText("day.$i.low", day.low),
          color = theme.secondaryText,
          fontSize = if (fill) HaType.Body else HaType.Caption,
          style = haTextStyle(),
          maxLines = 1,
        )
      }
    }
  }
}
