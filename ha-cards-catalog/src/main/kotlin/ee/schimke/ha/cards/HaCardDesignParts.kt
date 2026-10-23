@file:Suppress("RestrictedApi")

package ee.schimke.ha.cards

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.rc.cards.ArcDialDesignParts
import ee.schimke.ha.rc.cards.ButtonDesignParts
import ee.schimke.ha.rc.cards.ClockDesignParts
import ee.schimke.ha.rc.cards.EntityDesignParts
import ee.schimke.ha.rc.cards.GlanceDesignParts
import ee.schimke.ha.rc.cards.HeadingDesignParts
import ee.schimke.ha.rc.cards.SensorDesignParts
import ee.schimke.ha.rc.cards.TileDesignParts
import ee.schimke.ha.rc.design.CardDesigns

/*
 * The parts a card design is arranged from, on UI Builder's shelf: the switches that pick what a
 * card draws for the surface and size it is drawn at, and the forms of the card types migrated to their
 * designs. Each calls what the card design renderer calls for the same component id
 * (CardDesigns and the card types' `*DesignParts`), so a design edited in UI Builder draws in the app as it does
 * on the canvas. A slot wrapper draws the slot it picks inside its own modifier; the renderer hands
 * that modifier to the slot's node instead.
 */

/**
 * `ha-cards/card-size-mode`: [wrap] on a dashboard (the card sizes itself), [height] in a fixed
 * canvas whose height decides (a Wear widget container), [width] in one whose width decides (a
 * launcher cell).
 */
@Composable
@RemoteComposable
fun HaCardSizeMode(
  modifier: RemoteModifier = RemoteModifier,
  wrap: @Composable @RemoteComposable () -> Unit = {},
  height: @Composable @RemoteComposable () -> Unit = {},
  width: @Composable @RemoteComposable () -> Unit = {},
) {
  RemoteBox(modifier) { CardDesigns.SizeModeSwitch(wrap, height, width) }
}

/**
 * `ha-cards/size-tiers`: `tier<N>` for the canvas's size along [axis] (`width` or `height`) against
 * the ascending, comma-separated [thresholdsDp], picked where the document plays.
 */
@Composable
@RemoteComposable
fun HaSizeTiers(
  modifier: RemoteModifier = RemoteModifier,
  thresholdsDp: String = "96",
  axis: String = "width",
  tier0: @Composable @RemoteComposable () -> Unit = {},
  tier1: @Composable @RemoteComposable () -> Unit = {},
  tier2: @Composable @RemoteComposable () -> Unit = {},
) {
  CardDesigns.SizeTiers(thresholdsDp, axis, modifier) { tier ->
    when (tier) {
      0 -> tier0()
      1 -> tier1()
      else -> tier2()
    }
  }
}

/** `ha-cards/centered-cell`: [content] centred vertically in a fixed cell. */
@Composable
@RemoteComposable
fun HaCenteredCell(
  modifier: RemoteModifier = RemoteModifier,
  content: @Composable @RemoteComposable () -> Unit,
) {
  CardDesigns.Centered(modifier, content)
}

/** `ha-cards/tile-full`: the dashboard tile, icon beside name over state, with its features. */
@Composable
@RemoteComposable
fun HaTileFull(modifier: RemoteModifier = RemoteModifier) {
  TileDesignParts.Full(modifier)
}

/** `ha-cards/tile-compact`: icon, name and state, no features. */
@Composable
@RemoteComposable
fun HaTileCompact(modifier: RemoteModifier = RemoteModifier) {
  TileDesignParts.Compact(modifier)
}

/** `ha-cards/tile-icon-chip`: the tile's icon chip alone, for a cell too narrow for text. */
@Composable
@RemoteComposable
fun HaTileIconChip(modifier: RemoteModifier = RemoteModifier) {
  TileDesignParts.IconChip(modifier)
}

/** `ha-cards/tile-icon-hero`: a big icon over name and state, at the launcher cell's scale. */
@Composable
@RemoteComposable
fun HaTileIconHero(modifier: RemoteModifier = RemoteModifier) {
  TileDesignParts.IconHero(modifier)
}

/**
 * `ha-cards/tile-wide-hero`: a big icon beside name and state, [scale] `row` for a one-row launcher
 * cell or `wear` for a Wear container (no features, not live).
 */
@Composable
@RemoteComposable
fun HaTileWideHero(modifier: RemoteModifier = RemoteModifier, scale: String = "row") {
  TileDesignParts.WideHero(scale, modifier)
}

/**
 * `ha-cards/tile-features-gate`: a launcher cell for a tile with features, the full tile where
 * every feature row fits, else the compact one.
 */
@Composable
@RemoteComposable
fun HaTileFeaturesGate(modifier: RemoteModifier = RemoteModifier) {
  TileDesignParts.FeaturesGate(modifier)
}

/**
 * `ha-cards/tile-launcher-cell`: which launcher form fits the cell, [hero] or [row] when the cell
 * has a hero scale and the tile no features, [features] when it has features, else [other].
 */
@Composable
@RemoteComposable
fun HaTileLauncherCell(
  modifier: RemoteModifier = RemoteModifier,
  hero: @Composable @RemoteComposable () -> Unit = {},
  row: @Composable @RemoteComposable () -> Unit = {},
  features: @Composable @RemoteComposable () -> Unit = {},
  other: @Composable @RemoteComposable () -> Unit = {},
) {
  RemoteBox(modifier) { TileDesignParts.LauncherCell(hero, row, features, other) }
}

/**
 * `ha-cards/min-size-gate`: [roomy] where the canvas is at least [minWidthDp] × [minHeightDp],
 * [small] where it isn't, picked where the document plays.
 */
@Composable
@RemoteComposable
fun HaMinSizeGate(
  modifier: RemoteModifier = RemoteModifier,
  minWidthDp: Int = 96,
  minHeightDp: Int = 80,
  roomy: @Composable @RemoteComposable () -> Unit = {},
  small: @Composable @RemoteComposable () -> Unit = {},
) {
  CardDesigns.MinSizeGate(minWidthDp, minHeightDp, modifier) { if (it) roomy() else small() }
}

/**
 * `ha-cards/button-full`: the button, its icon over its name (and state), a toggle when its entity
 * is one. [compact] `grid` is the compact body in a square grid cell only, `true` always, `false`
 * never.
 */
@Composable
@RemoteComposable
fun HaButtonFull(modifier: RemoteModifier = RemoteModifier, compact: String = "grid") {
  ButtonDesignParts.Full(compact, modifier)
}

/**
 * `ha-cards/button-icon`: the button's icon chip, for a cell too small for the full button; the
 * name stays under it unless the icon is evocative.
 */
@Composable
@RemoteComposable
fun HaButtonIcon(modifier: RemoteModifier = RemoteModifier) {
  ButtonDesignParts.Icon(modifier)
}

/**
 * `ha-cards/button-name-row`: the icon beside the name, for a cell too short for the name under the
 * icon; the icon alone when the card hides its name.
 */
@Composable
@RemoteComposable
fun HaButtonNameRow(modifier: RemoteModifier = RemoteModifier) {
  ButtonDesignParts.NameRow(modifier)
}

/** `ha-cards/button-icon-hero`: a big icon over name and state, at the launcher cell's scale. */
@Composable
@RemoteComposable
fun HaButtonIconHero(modifier: RemoteModifier = RemoteModifier) {
  ButtonDesignParts.IconHero(modifier)
}

/** `ha-cards/button-wide-hero`: a big icon beside name and state, for a one-row launcher cell. */
@Composable
@RemoteComposable
fun HaButtonWideHero(modifier: RemoteModifier = RemoteModifier) {
  ButtonDesignParts.WideHero(modifier)
}

/**
 * `ha-cards/button-cell`: which slot the button and its canvas call for: [wrap] on a dashboard; in
 * a fixed canvas [fill] when the card hides its icon, [row] or [hero] for a launcher cell with a
 * hero scale, [wear] in a Wear container, [stated] for a card showing its name and state, else
 * [other].
 */
@Composable
@RemoteComposable
fun HaButtonCell(
  modifier: RemoteModifier = RemoteModifier,
  wrap: @Composable @RemoteComposable () -> Unit = {},
  fill: @Composable @RemoteComposable () -> Unit = {},
  row: @Composable @RemoteComposable () -> Unit = {},
  hero: @Composable @RemoteComposable () -> Unit = {},
  wear: @Composable @RemoteComposable () -> Unit = {},
  stated: @Composable @RemoteComposable () -> Unit = {},
  other: @Composable @RemoteComposable () -> Unit = {},
) {
  RemoteBox(modifier) { ButtonDesignParts.Cell(wrap, fill, row, hero, wear, stated, other) }
}

/**
 * `ha-cards/entity-card-row`: the entity's icon, name and state in a row; [inset] `card` insets it
 * by a card's horizontal padding, for a cell that is the card.
 */
@Composable
@RemoteComposable
fun HaEntityCardRow(modifier: RemoteModifier = RemoteModifier, inset: String = "none") {
  EntityDesignParts.Row(inset, modifier)
}

/**
 * `ha-cards/entity-card-hero`: a roomy launcher cell's form, a numeric reading's expanded
 * measurement or the icon hero; it fills its cell itself.
 */
@Composable
@RemoteComposable
fun HaEntityCardHero(modifier: RemoteModifier = RemoteModifier) {
  EntityDesignParts.Hero(modifier)
}

/**
 * `ha-cards/entity-card-wide-hero`: a big icon beside name and state, [scale] `row` for a one-row
 * launcher cell or `wear` for a Wear container (not live).
 */
@Composable
@RemoteComposable
fun HaEntityCardWideHero(modifier: RemoteModifier = RemoteModifier, scale: String = "row") {
  EntityDesignParts.WideHero(scale, modifier)
}

/** `ha-cards/entity-card-icon-chip`: the icon chip and state, for a cell too narrow for the row. */
@Composable
@RemoteComposable
fun HaEntityCardIconChip(modifier: RemoteModifier = RemoteModifier) {
  EntityDesignParts.IconChip(modifier)
}

/**
 * `ha-cards/entity-card-cell`: which slot the canvas calls for: [wrap] on a dashboard; in a fixed
 * canvas [hero] or [row] for a launcher cell with a hero scale, [wear] in a Wear container, else
 * [other].
 */
@Composable
@RemoteComposable
fun HaEntityCardCell(
  modifier: RemoteModifier = RemoteModifier,
  wrap: @Composable @RemoteComposable () -> Unit = {},
  hero: @Composable @RemoteComposable () -> Unit = {},
  row: @Composable @RemoteComposable () -> Unit = {},
  wear: @Composable @RemoteComposable () -> Unit = {},
  other: @Composable @RemoteComposable () -> Unit = {},
) {
  RemoteBox(modifier) { EntityDesignParts.Cell(wrap, hero, row, wear, other) }
}

/** `ha-cards/sensor-card-full`: the sensor card, its value over its history line. */
@Composable
@RemoteComposable
fun HaSensorCardFull(modifier: RemoteModifier = RemoteModifier) {
  SensorDesignParts.Full(modifier)
}

/**
 * `ha-cards/sensor-card-identity`: a narrow cell's identity, the reading's own visual over its
 * value or the icon chip; it fills its cell itself.
 */
@Composable
@RemoteComposable
fun HaSensorCardIdentity() {
  SensorDesignParts.Identity()
}

/** `ha-cards/sensor-card-history-hero`: a roomy launcher cell with a line, the value promoted. */
@Composable
@RemoteComposable
fun HaSensorCardHistoryHero(modifier: RemoteModifier = RemoteModifier) {
  SensorDesignParts.HistoryHero(modifier)
}

/**
 * `ha-cards/sensor-card-reading`: the reading in a [form] (`expanded`, `standard`, `compact`, or
 * `cell` for the launcher cell's width), inset by `m` or `l`; [live] `false` reads it without the
 * snapshot's history, as a Wear container does.
 */
@Composable
@RemoteComposable
fun HaSensorCardReading(
  modifier: RemoteModifier = RemoteModifier,
  form: String = "standard",
  inset: String = "none",
  live: Boolean = true,
) {
  SensorDesignParts.Reading(form, inset, live, modifier)
}

/**
 * `ha-cards/sensor-card-cell`: which slot the sensor and its canvas call for: [wrap] on a
 * dashboard, [wear] in a Wear container, [historyHero] or [readingHero] for a launcher cell with a
 * hero scale, [readingRow] for a wide, short one without a line, else [other].
 */
@Composable
@RemoteComposable
fun HaSensorCardCell(
  modifier: RemoteModifier = RemoteModifier,
  wrap: @Composable @RemoteComposable () -> Unit = {},
  wear: @Composable @RemoteComposable () -> Unit = {},
  historyHero: @Composable @RemoteComposable () -> Unit = {},
  readingHero: @Composable @RemoteComposable () -> Unit = {},
  readingRow: @Composable @RemoteComposable () -> Unit = {},
  other: @Composable @RemoteComposable () -> Unit = {},
) {
  RemoteBox(modifier) {
    SensorDesignParts.Cell(wrap, wear, historyHero, readingHero, readingRow, other)
  }
}

/** `ha-cards/heading-card-title`: the heading in its style, as a dashboard draws it. */
@Composable
@RemoteComposable
fun HaHeadingCardTitle(modifier: RemoteModifier = RemoteModifier) {
  HeadingDesignParts.Title(modifier)
}

/**
 * `ha-cards/heading-card-centred`: the heading centred in a fixed cell, a subtitle in a one-column
 * launcher cell and at the hero's size in a roomy one.
 */
@Composable
@RemoteComposable
fun HaHeadingCardCentred(modifier: RemoteModifier = RemoteModifier) {
  HeadingDesignParts.Centred(modifier)
}

/** `ha-cards/heading-card-cell`: [wrap] on a dashboard, [fixed] in a fixed canvas. */
@Composable
@RemoteComposable
fun HaHeadingCardCell(
  modifier: RemoteModifier = RemoteModifier,
  wrap: @Composable @RemoteComposable () -> Unit = {},
  fixed: @Composable @RemoteComposable () -> Unit = {},
) {
  RemoteBox(modifier) { HeadingDesignParts.Cell(wrap, fixed) }
}

/** `ha-cards/glance-card-grid`: the title over the grid of cells, fitted to a launcher cell. */
@Composable
@RemoteComposable
fun HaGlanceCardGrid(modifier: RemoteModifier = RemoteModifier) {
  GlanceDesignParts.Grid(modifier)
}

/**
 * `ha-cards/glance-card-cells`: the cells without the title, filling the canvas; [compact] drops
 * each cell's name.
 */
@Composable
@RemoteComposable
fun HaGlanceCardCells(modifier: RemoteModifier = RemoteModifier, compact: Boolean = false) {
  GlanceDesignParts.Cells(compact, modifier)
}

/** `ha-cards/glance-card-first-tile`: the first entity as a tile, for a one-cell launcher list. */
@Composable
@RemoteComposable
fun HaGlanceCardFirstTile(modifier: RemoteModifier = RemoteModifier) {
  GlanceDesignParts.FirstTile(modifier)
}

/**
 * `ha-cards/glance-card-cell`: [tile] in a launcher cell too narrow for a list, [wear] in a Wear
 * container, [other] otherwise.
 */
@Composable
@RemoteComposable
fun HaGlanceCardCell(
  modifier: RemoteModifier = RemoteModifier,
  tile: @Composable @RemoteComposable () -> Unit = {},
  wear: @Composable @RemoteComposable () -> Unit = {},
  other: @Composable @RemoteComposable () -> Unit = {},
) {
  RemoteBox(modifier) { GlanceDesignParts.Cell(tile, wear, other) }
}

/** `ha-cards/clock-card-face`: the time, its title and date line; [compact] is the time alone. */
@Composable
@RemoteComposable
fun HaClockCardFace(modifier: RemoteModifier = RemoteModifier, compact: Boolean = false) {
  ClockDesignParts.Face(compact, modifier)
}

/** `ha-cards/clock-card-cell`: [wear] in a Wear container, [other] otherwise. */
@Composable
@RemoteComposable
fun HaClockCardCell(
  modifier: RemoteModifier = RemoteModifier,
  wear: @Composable @RemoteComposable () -> Unit = {},
  other: @Composable @RemoteComposable () -> Unit = {},
) {
  RemoteBox(modifier) { ClockDesignParts.Cell(wear, other) }
}

/** `ha-cards/arc-dial-card-full`: a thermostat, humidifier or light dial over its steppers. */
@Composable
@RemoteComposable
fun HaArcDialCardFull(modifier: RemoteModifier = RemoteModifier) {
  ArcDialDesignParts.Full(modifier)
}

/**
 * `ha-cards/arc-dial-card-wear`: the dial's arc-left Wide row in a Wear container; [compact] is the
 * Small container's two lines; [keepsMode] keeps the mode chip under the name in the Large one.
 */
@Composable
@RemoteComposable
fun HaArcDialCardWear(
  modifier: RemoteModifier = RemoteModifier,
  compact: Boolean = false,
  keepsMode: Boolean = false,
) {
  ArcDialDesignParts.Wear(compact, keepsMode, modifier)
}

/** `ha-cards/arc-dial-card-launcher`: the dial's launcher ladder, fitted to the cell. */
@Composable
@RemoteComposable
fun HaArcDialCardLauncher(modifier: RemoteModifier = RemoteModifier) {
  ArcDialDesignParts.Launcher(modifier)
}
