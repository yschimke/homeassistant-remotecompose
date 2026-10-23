@file:Suppress("RestrictedApi")

package ee.schimke.ha.cards

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.design.CardDesign
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.CardDesignNode
import ee.schimke.ha.rc.design.CardDesigns

/**
 * A UI Builder card design (`.uid`) on the `ha-cards` catalog, drawn in this process: the card
 * types' own structural components and parts ([CardDesigns.components], which the converters draw
 * their cards with) plus this catalog's scalar wrappers ([components]). The library and the
 * scheduled import capture checked-in designs with it (server-owned-card-designs.md).
 */
class CardDesignDocument private constructor(private val design: CardDesign) {
  val id: String
    get() = design.id

  /** The catalog the design is pinned to; only `ha-cards` designs are drawn. */
  val catalog: String?
    get() = design.catalog

  /** Every reason this design can't be drawn here, by node; empty when it can. */
  fun validate(): List<String> = design.validate()

  /** The design, drawn with [modifier] on its root. */
  @Composable
  @RemoteComposable
  fun Render(modifier: RemoteModifier = RemoteModifier) = design.Render(modifier)

  /** [card] on [snapshot] drawn by this design, framed as `RenderChild` frames a card. */
  @Composable
  @RemoteComposable
  fun RenderCard(
    card: CardConfig,
    snapshot: HaSnapshot,
    modifier: RemoteModifier = RemoteModifier,
  ) = design.RenderCard(card, snapshot, modifier)

  companion object {
    const val CATALOG = CardDesign.CATALOG

    /** Components that are a whole HA card, drawing their own chrome. */
    private val WHOLE_CARDS =
      setOf(
        "ha-cards/entity-tile",
        "ha-cards/tile",
        "ha-cards/gauge",
        "ha-cards/button",
        "ha-cards/card-size-mode",
      )

    private fun CardDesignNode.color(name: String): RemoteColor? = argb(name)?.let { Color(it).rc }

    /**
     * This catalog's components, by id: its scalar wrappers over the `RemoteHa*` components, and a
     * dashboard card that puts anything but one whole card on the card surface.
     */
    val components: Map<String, CardDesignComponent> =
      mapOf(
        // A dashboard card holding one whole card is that card, with its own chrome: a tile in a
        // dashboard card is one card, not a card in a card. Anything else sits on the card surface.
        "ha-cards/dashboard-card" to
          { node, modifier ->
            val only = node.children("content").singleOrNull()
            if (only != null && node.componentOf(only) in WHOLE_CARDS) node.Child(only, modifier)
            else HaCardSurface(modifier) { node.Children() }
          },
        "ha-cards/dashboard-section" to
          { node, modifier ->
            HaDashboardSection(modifier) { node.Children() }
          },
        "ha-cards/card-surface" to
          { node, modifier ->
            HaCardSurface(modifier) { node.Children() }
          },
        "ha-cards/card-template" to
          { node, modifier ->
            HaCardTemplate(modifier) { node.Children() }
          },
        "ha-cards/uber-theme" to
          { node, modifier ->
            HaCardUberTheme(node.string("theme", "classic"), modifier, node.bool("dark")) {
              node.Children()
            }
          },
        "ha-cards/entity-tile" to
          { node, modifier ->
            HaCardEntityTile(
              modifier,
              domain = node.string("domain"),
              deviceClass = node.string("deviceClass"),
              iconAttribute = node.string("iconAttribute"),
              iconConfig = node.string("iconConfig"),
              unit = node.string("unit"),
            )
          },
        "ha-cards/tile" to
          { node, modifier ->
            HaCardTile(
              node.string("name"),
              node.string("state"),
              modifier,
              entityId = node.string("entityId"),
              icon = node.string("icon", "light"),
              toggleable = node.bool("toggleable"),
              isOn = node.bool("isOn"),
              activeColor = node.color("activeColor") ?: HaCardColors.Active,
              inactiveColor = node.color("inactiveColor") ?: HaCardColors.Inactive,
            )
          },
        "ha-cards/entity-row" to
          { node, modifier ->
            HaCardEntityRow(
              node.string("name"),
              node.string("state"),
              modifier,
              entityId = node.string("entityId"),
              icon = node.string("icon", "light"),
              toggleable = node.bool("toggleable"),
              isOn = node.bool("isOn"),
              activeColor = node.color("activeColor") ?: HaCardColors.Active,
              inactiveColor = node.color("inactiveColor") ?: HaCardColors.Inactive,
            )
          },
        "ha-cards/gauge" to
          { node, modifier ->
            HaCardGauge(
              node.string("name"),
              node.float("value"),
              modifier,
              min = node.float("min", 0f),
              max = node.float("max", 100f),
              unit = node.string("unit"),
              entityId = node.string("entityId"),
            )
          },
        "ha-cards/heading" to
          { node, modifier ->
            HaCardHeading(node.string("title"), modifier, node.bool("subtitle"))
          },
        "ha-cards/button" to
          { node, modifier ->
            HaCardButton(
              node.string("name"),
              modifier,
              entityId = node.string("entityId"),
              icon = node.string("icon", "light"),
              showName = node.bool("showName", true),
              toggleable = node.bool("toggleable"),
              isOn = node.bool("isOn"),
              activeColor = node.color("activeColor") ?: HaCardColors.Active,
              inactiveColor = node.color("inactiveColor") ?: HaCardColors.Inactive,
            )
          },
        "ha-cards/toggle" to
          { node, modifier ->
            HaCardToggle(
              node.bool("isOn"),
              modifier,
              entityId = node.string("entityId"),
              activeColor = node.color("activeColor") ?: HaCardColors.Active,
              inactiveColor = node.color("inactiveColor") ?: HaCardColors.Inactive,
            )
          },
        "ha-cards/icon" to
          { node, modifier ->
            HaCardIcon(
              node.string("icon", "light"),
              modifier,
              color = node.color("color") ?: HaCardColors.Active,
              sizeDp = node.int("sizeDp", 24),
            )
          },
        "ha-cards/metric" to
          { node, modifier ->
            HaCardMetric(
              node.string("label"),
              node.string("value"),
              modifier,
              unit = node.string("unit"),
              entityId = node.string("entityId"),
              valueColor = node.color("valueColor"),
            )
          },
      )

    /** The design in [text], a `.uid` document. */
    fun parse(text: String): CardDesignDocument =
      CardDesignDocument(CardDesign.parse(text, CardDesigns.components + components))
  }
}
