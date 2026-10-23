@file:Suppress("RestrictedApi")

package ee.schimke.ha.cards

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.rc.cards.TileDesignParts
import ee.schimke.ha.rc.design.CardDesigns

/*
 * The parts a card design is arranged from, on UI Builder's shelf: the switches that pick what a
 * card draws for the surface and size it is drawn at, and the forms of a tile. Each calls what the
 * card design renderer calls for the same component id (CardDesigns, TileDesignParts), so a design
 * edited in UI Builder draws in the app as it does on the canvas. A slot wrapper draws the slot it
 * picks inside its own modifier; the renderer hands that modifier to the slot's node instead.
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
  RemoteBox(modifier) { CardDesigns.Centered(content) }
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
