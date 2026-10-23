@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

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
 * which surface, is the design's: these only draw.
 * - `ha-cards/tile-full`: the dashboard tile, icon beside name over state, with its features.
 * - `ha-cards/tile-compact`: icon, name and state, no features, colours resolved when captured.
 * - `ha-cards/tile-icon-chip`: the icon chip alone, for a cell too narrow for text.
 * - `ha-cards/tile-icon-hero`: a big icon over name and state, at the launcher cell's scale.
 * - `ha-cards/tile-wide-hero`: a big icon beside name and state: `scale` `row` (a one-row launcher
 *   cell) or `wear` (a Wear container, no features, not live).
 * - `ha-cards/tile-features-gate`: a launcher cell for a tile with features: the full tile where
 *   every feature row fits, else the compact one.
 * - `ha-cards/tile-launcher-cell`: which launcher form fits the cell: `hero` or `row` when the cell
 *   has a hero scale and the tile no features, `features` when it has features, else `other`.
 */
internal object TileDesignParts {
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

  val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/tile-full" to
        { _, modifier ->
          val (card, snapshot) = input()
          RemoteHaTile(converter.tileData(card, snapshot), modifier = modifier)
        },
      "ha-cards/tile-compact" to
        { _, modifier ->
          val (card, snapshot) = input()
          RemoteHaTileCompact(converter.tileData(card, snapshot), modifier)
        },
      "ha-cards/tile-icon-chip" to
        { _, modifier ->
          val (card, snapshot) = input()
          RemoteHaIconChip(
            converter.tileData(card, snapshot),
            modifier,
            bindState = LocalWidgetCaptureSizeDp.current != null,
          )
        },
      "ha-cards/tile-icon-hero" to
        { _, modifier ->
          val (card, snapshot) = input()
          RemoteHaIconHero(converter.tileData(card, snapshot), captureHeroScale()!!, modifier)
        },
      "ha-cards/tile-wide-hero" to
        { node, modifier ->
          val (card, snapshot) = input()
          if (node.string("scale", "row") == "wear")
            RemoteHaWideHero(
              converter.tileData(card, snapshot).copy(features = emptyList()),
              WearHeroScale,
              modifier,
              live = false,
            )
          else RemoteHaWideHero(converter.tileData(card, snapshot), captureRowScale()!!, modifier)
        },
      "ha-cards/tile-features-gate" to
        { _, modifier ->
          val (card, snapshot) = input()
          converter.FeaturesGate(converter.tileData(card, snapshot), modifier)
        },
      "ha-cards/tile-launcher-cell" to
        { node, modifier ->
          val (card, snapshot) = input()
          val plain = converter.features(card, snapshot).isEmpty()
          when {
            captureHeroScale() != null && plain -> node.Slot("hero", modifier)
            captureRowScale() != null && plain -> node.Slot("row", modifier)
            !plain -> node.Slot("features", modifier)
            else -> node.Slot("other", modifier)
          }
        },
    )
}
