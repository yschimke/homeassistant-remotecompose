@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import ee.schimke.ha.rc.components.RemoteHaIconChip
import ee.schimke.ha.rc.components.RemoteHaIconHero
import ee.schimke.ha.rc.components.RemoteHaTile
import ee.schimke.ha.rc.components.RemoteHaTileCompact
import ee.schimke.ha.rc.components.RemoteHaWideHero
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput
import ee.schimke.ha.rc.framedDesignTemplate

/**
 * The parts a tile's card design arranges (card-designs/ui-builder/designs/tile.uid): each draws
 * one form of the tile for the card being drawn ([LocalCardInput]), which the tile's data (name,
 * state, icon, accent, badge, features, meter glyph) comes from. Which form shows at which size, on
 * which surface, is the design's: these only draw. The card design renderer draws them through
 * [components]; ha-cards-catalog's `HaTile*` wrappers put the same calls on UI Builder's shelf.
 */
object TileDesignParts {
  private val converter = TileCardConverter()

  /** The card being drawn, or a placeholder light tile in UI Builder's canvas. */
  @Composable
  private fun input(): DesignTemplateInput =
    LocalCardInput.current
      ?: requireNotNull(
        converter.framedDesignTemplate(
          DesignProperties.build { string(TileCardConverter.DOMAIN, "light") }
        )
      )

  @Composable
  private fun data() = input().let { (card, snapshot) -> converter.tileData(card, snapshot) }

  /** `ha-cards/tile-full`: the dashboard tile, icon beside name over state, with its features. */
  @Composable
  fun Full(modifier: RemoteModifier) {
    RemoteHaTile(data(), modifier = modifier)
  }

  /** `ha-cards/tile-compact`: icon, name and state, no features, colours resolved when captured. */
  @Composable
  fun Compact(modifier: RemoteModifier) {
    RemoteHaTileCompact(data(), modifier)
  }

  /** `ha-cards/tile-icon-chip`: the icon chip alone, for a cell too narrow for text. */
  @Composable
  fun IconChip(modifier: RemoteModifier) {
    RemoteHaIconChip(data(), modifier, bindState = LocalWidgetCaptureSizeDp.current != null)
  }

  /**
   * `ha-cards/tile-icon-hero`: a big icon over name and state, at the launcher cell's scale (the
   * Wear scale outside a launcher capture).
   */
  @Composable
  fun IconHero(modifier: RemoteModifier) {
    RemoteHaIconHero(data(), captureHeroScale() ?: WearHeroScale, modifier)
  }

  /**
   * `ha-cards/tile-wide-hero`: a big icon beside name and state. [scale] `row` sizes it for a one-
   * row launcher cell (the Wear scale outside one); `wear` for a Wear container, without features
   * and not live.
   */
  @Composable
  fun WideHero(scale: String, modifier: RemoteModifier) {
    if (scale == "wear")
      RemoteHaWideHero(data().copy(features = emptyList()), WearHeroScale, modifier, live = false)
    else RemoteHaWideHero(data(), captureRowScale() ?: WearHeroScale, modifier)
  }

  /**
   * `ha-cards/tile-features-gate`: a launcher cell for a tile with features: the full tile where
   * every feature row fits, else the compact one.
   */
  @Composable
  fun FeaturesGate(modifier: RemoteModifier) {
    converter.FeaturesGate(data(), modifier)
  }

  /**
   * `ha-cards/tile-launcher-cell`: which launcher form fits the cell: [hero] or [row] when the cell
   * has a hero scale and the tile no features, [features] when it has features, else [other].
   */
  @Composable
  fun LauncherCell(
    hero: @Composable () -> Unit,
    row: @Composable () -> Unit,
    features: @Composable () -> Unit,
    other: @Composable () -> Unit,
  ) {
    val (card, snapshot) = input()
    val plain = converter.features(card, snapshot).isEmpty()
    when {
      captureHeroScale() != null && plain -> hero()
      captureRowScale() != null && plain -> row()
      !plain -> features()
      else -> other()
    }
  }

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/tile-full" to { _, modifier -> Full(modifier) },
      "ha-cards/tile-compact" to { _, modifier -> Compact(modifier) },
      "ha-cards/tile-icon-chip" to { _, modifier -> IconChip(modifier) },
      "ha-cards/tile-icon-hero" to { _, modifier -> IconHero(modifier) },
      "ha-cards/tile-wide-hero" to
        { node, modifier ->
          WideHero(node.string("scale", "row"), modifier)
        },
      "ha-cards/tile-features-gate" to { _, modifier -> FeaturesGate(modifier) },
      "ha-cards/tile-launcher-cell" to
        { node, modifier ->
          LauncherCell(
            hero = { node.Slot("hero", modifier) },
            row = { node.Slot("row", modifier) },
            features = { node.Slot("features", modifier) },
            other = { node.Slot("other", modifier) },
          )
        },
    )
}
