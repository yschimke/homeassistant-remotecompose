package ee.schimke.terrazzo.wearsync

import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DeviceMatch
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.terrazzo.dashboard.CardDesigns
import ee.schimke.terrazzo.dashboard.cardDevice
import ee.schimke.terrazzo.wearsync.proto.WearSlotDesign

/**
 * The Wear widget design each slot card gets, per container: the design server's Wear widget design
 * made from the design the card draws with on the phone ([DesignKey.forWearWidget] of its key in
 * the card's variant, then `default`, its device's design first), so a watch slot shows the same
 * design as the dashboard, rooted on the Wear container. The watch is dark, so it is the card's
 * dark design. A card or container the server has no Wear design for is left out: the watch draws
 * the card's own design there, as it always has.
 *
 * Empty while no design server is configured or its designs haven't loaded.
 *
 * @param cards each slot's card, by its pin's `cardKey`.
 */
internal fun wearSlotDesigns(
  cards: List<Pair<String, CardConfig>>,
  snapshot: HaSnapshot,
  designs: CardDesigns,
): List<Pair<WearSlotDesign, ByteArray>> {
  val choice = designs.loadedFor ?: return emptyList()
  return cards.flatMap { (cardKey, card) ->
    val override = choice.overrides.forCard(card)
    val cardStyle = override?.cardStyle?.let(HaUberTheme::byId) ?: choice.cardStyle
    val design = CardDesignTemplates.designCard(card, snapshot)
    val key =
      CardDesignTemplates.keyFor(design, snapshot, choice.style, dark = true, cardStyle = cardStyle)
        ?: return@flatMap emptyList()
    val variants = listOf(key.copy(variant = override?.variant ?: designs.variant), key).distinct()
    val device = cardDevice(design, snapshot)
    DesignKey.WEAR_CONTAINERS.mapNotNull { container ->
      val keys = variants.map { it.forWearWidget(container) }
      val bytes =
        keys.firstNotNullOfOrNull { k ->
          device?.let { DeviceMatch.pick(designs.deviceByKey[k].orEmpty(), it) }
        } ?: keys.firstNotNullOfOrNull { designs.byKey[it] }
      bytes
        ?.takeIf { it.size <= WEAR_DESIGN_MAX_BYTES }
        ?.let {
          WearSlotDesign(cardKey, container, ee.schimke.ha.client.design.Sha256.hex(it)) to it
        }
    }
  }
}

/** One DataItem's size limit, with headroom: a bigger design stays on the phone. */
internal const val WEAR_DESIGN_MAX_BYTES = 90_000
