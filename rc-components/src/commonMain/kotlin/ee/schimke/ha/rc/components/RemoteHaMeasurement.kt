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
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteTextUnit
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow

// ---------------------------------------------------------------------------------------------
// Layer 3 of the card data hierarchy: typed measurements. A measurement is a reading drawn the
// way its kind deserves — a temperature as a thermometer, a battery as a battery — at one of four
// presentations. The presentations are the adaptive ladder of adaptive-card-layouts.md, shared by
// every kind so a thermometer and a battery shrink and grow the same way:
//
//   Glyph     the visual alone, filling its box          identity        (P1)
//   Chip      the visual over the value, filling the     identity+value  (P1 P3)
//             cell — for narrow cells (a 1×1 launcher)
//   Compact   small visual beside value + name           reflowed        (P1 P2 P3)
//   Standard  card-sized visual beside value + name      full            (P1 P2 P3)
//   Expanded  visual grows with the cell; name, value    expanded        (P1 P2 P3 P4)
//             and the scale's range around it
//
// Glyph sizes to its modifier; Compact and Standard wrap their content; Chip and Expanded fill the
// cell they are given (use them in a fixed-size cell: a launcher widget, a Wear container, a sized
// dashboard slot). Picking the
// presentation from the cell's live size is the converter's job (`RemoteHaAdaptiveMeasurement`
// in :rc-converter), since only it knows the host's breakpoint support.
// ---------------------------------------------------------------------------------------------

/** How much of a measurement to show: see the ladder above. */
enum class HaPresentation {
  Glyph,
  Chip,
  Compact,
  Standard,
  Expanded;

  companion object {
    /** Parse a builder / config spelling; unknown is [Standard]. */
    fun parse(name: String): HaPresentation =
      entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: Standard
  }
}

/**
 * The visual's box, in dp, beside the text in [HaPresentation.Compact] / [HaPresentation.Standard].
 */
internal fun HaMeasureVisual.glyphSizeDp(presentation: HaPresentation): Pair<Int, Int> =
  when (presentation) {
    HaPresentation.Compact ->
      when (this) {
        HaMeasureVisual.Thermometer -> 16 to 40
        HaMeasureVisual.Battery -> 40 to 20
        HaMeasureVisual.Arc -> 48 to 26
        HaMeasureVisual.Bar -> 48 to 8
      }
    else ->
      when (this) {
        HaMeasureVisual.Thermometer -> 26 to 68
        HaMeasureVisual.Battery -> 60 to 30
        HaMeasureVisual.Arc -> 84 to 44
        HaMeasureVisual.Bar -> 84 to 10
      }
  }

/**
 * A reading's visual alone, by its kind's [HaMeasureVisual], filling [modifier]'s box at the
 * visual's own aspect. This is the measurement's identity: present at every presentation.
 */
@Composable
@RemoteComposable
fun RemoteHaMeasurementGlyph(reading: HaReading, modifier: RemoteModifier = RemoteModifier) {
  val fraction = reading.fraction()
  val color = reading.color()
  val zones = reading.zones()
  when (reading.kind.visual) {
    HaMeasureVisual.Thermometer ->
      RemoteHaThermometerGlyph(fraction, color, modifier, zones = zones)
    HaMeasureVisual.Battery -> RemoteHaBatteryGlyph(fraction, color, modifier, zones = zones)
    HaMeasureVisual.Arc -> RemoteHaArcMeter(fraction, color, modifier, zones = zones)
    HaMeasureVisual.Bar -> RemoteHaBarMeter(fraction, color, modifier, zones = zones)
  }
}

/**
 * A reading at [presentation], drawn by its kind. Draws no chrome: put it in a card
 * (`HaCardSurface`, a converter's card) or a widget container.
 */
@Composable
@RemoteComposable
fun RemoteHaMeasurement(
  reading: HaReading,
  presentation: HaPresentation = HaPresentation.Standard,
  modifier: RemoteModifier = RemoteModifier,
  tapAction: HaAction = HaAction.None,
  /**
   * [HaPresentation.Chip] only: draw the name between the visual and the value, for a reading whose
   * visual is the same in every room (card-information-hierarchy.md, the icon exception).
   */
  chipShowsName: Boolean = false,
) {
  val clickable = tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  when (presentation) {
    HaPresentation.Glyph ->
      RemoteBox(modifier = modifier.then(clickable)) {
        RemoteHaMeasurementGlyph(reading, RemoteModifier.fillMaxSize())
      }
    HaPresentation.Chip -> Chip(reading, modifier.then(clickable), chipShowsName)
    HaPresentation.Compact,
    HaPresentation.Standard -> SideBySide(reading, presentation, modifier.then(clickable))
    HaPresentation.Expanded -> Expanded(reading, modifier.then(clickable))
  }
}

/** HA `temperature` reading as a thermometer. */
@Composable
@RemoteComposable
fun RemoteHaThermometer(
  reading: HaReading,
  presentation: HaPresentation = HaPresentation.Standard,
  modifier: RemoteModifier = RemoteModifier,
  tapAction: HaAction = HaAction.None,
) =
  RemoteHaMeasurement(
    reading.copy(kind = HaMeasureKind.Temperature),
    presentation,
    modifier,
    tapAction,
  )

/** HA `battery` reading as a battery, amber below 30 % and red below 15 %. */
@Composable
@RemoteComposable
fun RemoteHaBattery(
  reading: HaReading,
  presentation: HaPresentation = HaPresentation.Standard,
  modifier: RemoteModifier = RemoteModifier,
  tapAction: HaAction = HaAction.None,
) =
  RemoteHaMeasurement(reading.copy(kind = HaMeasureKind.Battery), presentation, modifier, tapAction)

/** Compact and Standard: the visual beside value over name. */
@Composable
private fun SideBySide(reading: HaReading, presentation: HaPresentation, modifier: RemoteModifier) {
  val compact = presentation == HaPresentation.Compact
  val plate = currentHaUberTheme().theme.readout == HaReadoutStyle.Plate
  val (glyphW, glyphH) = reading.kind.visual.glyphSizeDp(presentation)
  val valueSize = if (compact) HaType.ValueSmall else HaType.Value
  val nameSize = if (compact) HaType.Caption else HaType.Label
  // Column → Row, never a bare Row root: a row directly under a breakpoint's wrap-content box
  // captures blank (adaptive-card-layouts.md, Known gaps #9).
  RemoteColumn(modifier = modifier, verticalArrangement = RemoteArrangement.Center) {
    RemoteRow(
      modifier = RemoteModifier.fillMaxWidth(),
      verticalAlignment = RemoteAlignment.CenterVertically,
      horizontalArrangement =
        RemoteArrangement.spacedBy((if (compact) HaSpace.M else HaSpace.L).rdp),
    ) {
      RemoteHaMeasurementGlyph(reading, RemoteModifier.size(glyphW.rdp, glyphH.rdp))
      RemoteColumn(
        modifier = RemoteModifier.weight(1f),
        verticalArrangement = RemoteArrangement.spacedBy(HaSpace.XXS.rdp),
      ) {
        if (plate) {
          // A tracked label ruled off above the reading, like a hi-fi front panel.
          NameLabel(reading, nameSize, ruled = true)
          ValueReadout(reading, valueSize)
        } else {
          ValueReadout(reading, valueSize)
          NameLabel(reading, nameSize)
        }
      }
    }
  }
}

/**
 * Chip: the visual taking the cell's height above the value — identity and value, no name. The
 * value is set at label size, as the icon chip's: a chip is for a cell one launcher column wide,
 * and a value the cell cuts short (`21.…`) is no value at all.
 */
@Composable
private fun Chip(reading: HaReading, modifier: RemoteModifier, showName: Boolean) {
  RemoteColumn(
    modifier = modifier.fillMaxSize().padding(HaSpace.XS.rdp),
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
    verticalArrangement =
      if (showName) RemoteArrangement.spacedBy(HaSpace.XXS.rdp, RemoteAlignment.CenterVertically)
      else RemoteArrangement.spacedBy(HaSpace.XS.rdp),
  ) {
    if (showName) {
      // Three parts in a 1×1 cell's ~76dp: the visual at a fixed 30dp, so the name and the value
      // under it (~16 + ~17dp) are never pushed past the cell's edge.
      RemoteHaMeasurementGlyph(reading, RemoteModifier.fillMaxWidth().height(30.rdp))
      NameLabel(reading, HaType.Caption)
    } else {
      RemoteHaMeasurementGlyph(reading, RemoteModifier.fillMaxWidth().weight(1f))
    }
    ValueReadout(reading, HaType.Label)
  }
}

/**
 * Expanded: name on top, the visual taking every dp the cell has left (so it grows with the cell),
 * then the value promoted to [HaType.ValueLarge] and the scale's range (P4) under it.
 */
@Composable
private fun Expanded(reading: HaReading, modifier: RemoteModifier) {
  if (visualFamily().isCommunity) {
    CommunityReadingLayout(
      reading.nameText(),
      reading.text,
      reading.rangeText(),
      reading.fraction(),
      reading.color(),
      modifier.fillMaxSize().padding(6.rdp),
      small = true,
    ) {
      RemoteHaMeasurementGlyph(reading, it)
    }
    return
  }
  val theme = haTheme()
  val plate = currentHaUberTheme().theme.readout == HaReadoutStyle.Plate
  RemoteColumn(
    modifier = modifier.fillMaxSize().padding(HaSpace.M.rdp),
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
    verticalArrangement = RemoteArrangement.spacedBy(HaSpace.XS.rdp),
  ) {
    NameLabel(reading, HaType.Body, ruled = plate)
    if (reading.kind.visual == HaMeasureVisual.Bar) {
      // A bar keeps its thickness; the spare height centres it rather than fattening it.
      RemoteColumn(modifier = RemoteModifier.fillMaxWidth().weight(1f)) {
        RemoteBox(modifier = RemoteModifier.fillMaxWidth().weight(1f))
        RemoteHaMeasurementGlyph(reading, RemoteModifier.fillMaxWidth().height(12.rdp))
        RemoteBox(modifier = RemoteModifier.fillMaxWidth().weight(1f))
      }
    } else {
      RemoteHaMeasurementGlyph(reading, RemoteModifier.fillMaxWidth().weight(1f))
    }
    ValueReadout(reading, HaType.ValueLarge)
    RemoteText(
      text = reading.rangeText(),
      color = theme.secondaryText,
      fontSize = HaType.Caption,
      style = haTextStyle(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * The reading's value, set the uber theme's way ([HaReadoutStyle]): plain, on a tonal pill
 * (Expressive), or in a recessed screen panel (LCD).
 */
@Composable
private fun ValueReadout(reading: HaReading, size: RemoteTextUnit) {
  val theme = haTheme()
  val value: @Composable () -> Unit = {
    RemoteText(
      text = reading.text,
      color = theme.primaryText,
      fontSize = size,
      fontWeight = haValueWeight(),
      style = haTextStyle(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
  when (currentHaUberTheme().theme.readout) {
    HaReadoutStyle.Plain,
    HaReadoutStyle.Plate -> value()
    HaReadoutStyle.TonalPill ->
      RemoteBox(
        modifier =
          RemoteModifier.clip(RemoteRoundedCornerShape((HaShape.Chip * 2).rdp))
            .background(theme.placeholderBackground)
            .padding(horizontal = HaSpace.L.rdp, vertical = HaSpace.XXS.rdp)
      ) {
        value()
      }
    HaReadoutStyle.Screen ->
      RemoteBox(
        modifier =
          RemoteModifier.clip(RemoteRoundedCornerShape(HaSpace.XXS.rdp))
            .background(theme.dashboardBackground)
            .padding(horizontal = HaSpace.S.rdp, vertical = HaSpace.Hairline.rdp)
      ) {
        value()
      }
  }
}

/** The reading's name in the theme's secondary text; [ruled] adds a hairline under it. */
@Composable
private fun NameLabel(reading: HaReading, size: RemoteTextUnit, ruled: Boolean = false) {
  val theme = haTheme()
  RemoteColumn {
    RemoteText(
      text = reading.nameText(),
      color = theme.secondaryText,
      fontSize = size,
      style = haTextStyle(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (ruled) {
      RemoteBox(
        modifier =
          RemoteModifier.padding(top = HaSpace.XXS.rdp)
            .fillMaxWidth()
            .height(HaSpace.Hairline.rdp)
            .background(theme.divider)
      )
    }
  }
}
