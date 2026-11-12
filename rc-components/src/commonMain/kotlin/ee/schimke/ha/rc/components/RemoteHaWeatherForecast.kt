@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.compose.capture.toRemoteImageVector
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.clip
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxHeight
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.modifier.width
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow
import ee.schimke.composeai.rccreation.foundation.icon.RemoteBasicIcon

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
  showCondition: Boolean = true,
) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 12.rdp),
  ) {
    RemoteColumn(
      modifier = if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier.fillMaxWidth(),
      verticalArrangement = RemoteArrangement.spacedBy(10.rdp),
    ) {
      CurrentRow(data, theme, showCondition)
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
            modifier = RemoteModifier.fillMaxWidth().weight(1f.rf),
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
private fun CurrentRow(data: HaWeatherForecastData, theme: RemoteHaTheme, showCondition: Boolean) {
  RemoteRow(
    modifier = RemoteModifier.fillMaxWidth(),
    verticalAlignment = RemoteAlignment.CenterVertically,
    horizontalArrangement = RemoteArrangement.SpaceBetween,
  ) {
    // The condition takes what the temperature leaves: in a narrow card it is the words that
    // shorten, never the reading.
    RemoteRow(
      modifier = RemoteModifier.weight(1f.rf).padding(end = 8.rdp),
      verticalAlignment = RemoteAlignment.CenterVertically,
    ) {
      RemoteBasicIcon(
        imageVector = data.icon.toRemoteImageVector(),
        contentDescription = data.condition,
        modifier = RemoteModifier.size(36.rdp),
        tint = theme.primaryText,
      )
      // Without [showCondition] (a card too narrow for the words beside the temperature) the icon
      // says the condition.
      if (showCondition)
        RemoteColumn(modifier = RemoteModifier.padding(start = 10.rdp)) {
          RemoteText(
            text = data.condition,
            color = theme.primaryText,
            fontSize = HaType.Title,
            fontWeight = RemoteFontWeight.Medium,
            style = haTextStyle(),
            maxLines = 1,
            overflow = RemoteTextOverflow.Ellipsis,
          )
          if (data.supportingLine != null) {
            RemoteText(
              text = data.supportingLine.rs,
              color = theme.secondaryText,
              fontSize = HaType.Label,
              style = haTextStyle(),
              maxLines = 1,
              overflow = RemoteTextOverflow.Ellipsis,
            )
          } else {
            RemoteText(
              text = entityName(data.entityId, data.name),
              color = theme.secondaryText,
              fontSize = HaType.Label,
              style = haTextStyle(),
              maxLines = 1,
              overflow = RemoteTextOverflow.Ellipsis,
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
        overflow = RemoteTextOverflow.Ellipsis,
      )
      if (data.highLow != null) {
        RemoteText(
          text = cardText("high_low", data.highLow),
          color = theme.secondaryText,
          fontSize = HaType.Body,
          style = haTextStyle(),
          maxLines = 1,
          overflow = RemoteTextOverflow.Ellipsis,
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
      ExtraChip(i, extra, theme, RemoteModifier.weight(1f.rf))
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
    RemoteBasicIcon(
      imageVector = extra.icon.toRemoteImageVector(),
      contentDescription = extra.label.rs,
      modifier = RemoteModifier.size(16.rdp),
      tint = theme.secondaryText,
    )
    RemoteText(
      text = cardText("extra.$i", extra.value),
      color = theme.primaryText,
      fontSize = HaType.Body,
      fontWeight = RemoteFontWeight.Medium,
      style = haTextStyle(),
      maxLines = 1,
      overflow = RemoteTextOverflow.Ellipsis,
    )
    RemoteText(
      text = extra.label.rs,
      color = theme.secondaryText,
      fontSize = HaType.Micro,
      style = haTextStyle(),
      maxLines = 1,
      overflow = RemoteTextOverflow.Ellipsis,
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
    RemoteBasicIcon(
      imageVector = data.icon.toRemoteImageVector(),
      contentDescription = data.condition,
      modifier = RemoteModifier.size(28.rdp),
      tint = theme.primaryText,
    )
    // The 8 dp gaps either side of the text are its own padding, not a `spacedBy` arrangement:
    // the RemoteCompose row doesn't subtract `spacedBy` gaps from the weighted share, which pushed
    // the temperature 16 dp past the slot's edge (clipped on Wear and the 4×1 widget).
    RemoteColumn(
      modifier = RemoteModifier.weight(1f.rf).padding(horizontal = 8.rdp),
      verticalArrangement = RemoteArrangement.Center,
    ) {
      RemoteText(
        text = data.condition,
        color = theme.primaryText,
        fontSize = HaType.Body,
        fontWeight = RemoteFontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
      )
      RemoteText(
        text = data.supportingLine?.rs ?: entityName(data.entityId, data.name),
        color = theme.secondaryText,
        fontSize = HaType.Caption,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
      )
    }
    RemoteText(
      text = LiveValues.attribute(data.entityId, "temperature_label", data.temperature),
      color = theme.primaryText,
      fontSize = HaType.ValueSmall,
      fontWeight = haValueWeight(),
      style = haTextStyle(),
      maxLines = 1,
      overflow = RemoteTextOverflow.Ellipsis,
    )
  }
}

/**
 * The weather in a launcher widget one column wide (`57 dp`): the condition's icon over the
 * temperature, centred. No row fits that width beside the icon, and the condition's words would be
 * cut to a letter; the icon says it.
 */
@Composable
@RemoteComposable
fun RemoteHaWeatherForecastNarrow(
  data: HaWeatherForecastData,
  modifier: RemoteModifier = RemoteModifier,
) {
  val theme = haTheme()
  RemoteColumn(
    modifier = modifier.fillMaxSize().then(cardChrome(theme.cardBackground, theme.divider)),
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
    verticalArrangement = RemoteArrangement.spacedBy(4.rdp, RemoteAlignment.CenterVertically),
  ) {
    RemoteBasicIcon(
      imageVector = data.icon.toRemoteImageVector(),
      contentDescription = data.condition,
      modifier = RemoteModifier.size(28.rdp),
      tint = theme.primaryText,
    )
    RemoteText(
      text = LiveValues.attribute(data.entityId, "temperature_label", data.temperature),
      color = theme.primaryText,
      fontSize = HaType.Label,
      fontWeight = haValueWeight(),
      style = haTextStyle(),
      maxLines = 1,
    )
  }
}

/**
 * The weather in a Wear slot. The Wide row's three columns leave the condition a few letters in a
 * watch's width (`Par…`), so this one stacks its text beside the icon: the condition over the
 * temperature, both whole. The Small container (about 44 dp of content) drops the entity's name
 * (`Forecast Home`) to fit; [large] keeps it under them, with a bigger icon and value.
 */
@Composable
@RemoteComposable
fun RemoteHaWeatherForecastWear(
  data: HaWeatherForecastData,
  modifier: RemoteModifier = RemoteModifier,
  large: Boolean = false,
) {
  val theme = haTheme()
  RemoteRow(
    modifier =
      modifier
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 12.rdp, vertical = (if (large) 8 else 2).rdp),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    RemoteBasicIcon(
      imageVector = data.icon.toRemoteImageVector(),
      contentDescription = data.condition,
      modifier = RemoteModifier.size((if (large) 40 else 30).rdp),
      tint = theme.primaryText,
    )
    RemoteColumn(
      modifier = RemoteModifier.weight(1f.rf).padding(start = 10.rdp),
      verticalArrangement = RemoteArrangement.Center,
    ) {
      RemoteText(
        text = data.condition,
        color = theme.primaryText,
        fontSize = if (large) HaType.Body else HaType.Label,
        fontWeight = RemoteFontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
      )
      RemoteText(
        text = LiveValues.attribute(data.entityId, "temperature_label", data.temperature),
        color = theme.primaryText,
        fontSize = if (large) HaType.Value else HaType.Title,
        fontWeight = haValueWeight(),
        style = haTextStyle(),
        maxLines = 1,
      )
      if (large)
        RemoteText(
          text = data.supportingLine?.rs ?: entityName(data.entityId, data.name),
          color = theme.secondaryText,
          fontSize = HaType.Caption,
          style = haTextStyle(),
          maxLines = 1,
          overflow = RemoteTextOverflow.Ellipsis,
        )
    }
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
          if (fill) RemoteModifier.weight(1f.rf).fillMaxHeight() else RemoteModifier.weight(1f.rf),
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
        RemoteBasicIcon(
          imageVector = day.icon.toRemoteImageVector(),
          contentDescription = label,
          modifier = RemoteModifier.size(if (fill) 32.rdp else 20.rdp),
          tint = theme.primaryText,
        )
        RemoteText(
          text = cardText("day.$i.high", day.high),
          color = theme.primaryText,
          fontSize = if (fill) HaType.BodyLarge else HaType.Label,
          fontWeight = RemoteFontWeight.Medium,
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
