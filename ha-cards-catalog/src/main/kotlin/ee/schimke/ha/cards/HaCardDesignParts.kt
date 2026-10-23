@file:Suppress("RestrictedApi")

package ee.schimke.ha.cards

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.rc.cards.ButtonDesignParts
import ee.schimke.ha.rc.cards.TileDesignParts
import ee.schimke.ha.rc.design.CardDesigns

/*
 * The parts a card design is arranged from, on UI Builder's shelf: the switches that pick what a
 * card draws for the surface and size it is drawn at, and the forms of a tile and a button. Each
 * calls what the card design renderer calls for the same component id (CardDesigns,
 * TileDesignParts, ButtonDesignParts), so a design edited in UI Builder draws in the app as it does
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
