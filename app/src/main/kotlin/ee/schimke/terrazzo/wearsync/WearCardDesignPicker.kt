package ee.schimke.terrazzo.wearsync

import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DeviceMatch
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.terrazzo.core.pin.MobilePinnedCard
import ee.schimke.terrazzo.core.pin.MobilePinnedSection
import ee.schimke.terrazzo.dashboard.CardDesigns
import ee.schimke.terrazzo.dashboard.cardDevice
import ee.schimke.terrazzo.wearsync.proto.WearSlotDesign

/**
 * The template each Wear card gets, per container: the design server's Wear widget design made from
 * the design the card draws with on the phone ([DesignKey.forWearWidget] of its key in the card's
 * variant, then `default`, its device's design first), so a watch widget showing the card draws the
 * same design as the dashboard, rooted on the Wear container. Every pinned card gets them: the
 * watch chooses which card a widget shows, from the cards it was sent. The watch is dark, so it is
 * the card's dark design. A card or container the server has no Wear design for is sent its phone
 * template, adapted to the Wear frame by the watch.
 *
 * Empty while no design server is configured or its designs haven't loaded.
 *
 * @param cards by pin key (older widgets) or received config fingerprint (every Wear surface).
 */
internal fun wearCardDesigns(
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
      // A selected installed skin may only ship a phone template. Send that template rather than
      // silently substituting a generic Wear design; the watch adapts its card box to its frame.
      val selected =
        override?.designId?.let { id ->
          designs.byDesign.find(id, keys.first(), device)?.second
            ?: designs.byDesign.find(id, variants.first(), device)?.second
        }
      // The design the card was switched to, when it has a Wear widget design for the container.
      val bytes =
        selected
          ?: keys.firstNotNullOfOrNull { k ->
            device?.let { DeviceMatch.pick(designs.deviceByKey[k].orEmpty(), it) }
          }
          ?: keys.firstNotNullOfOrNull { designs.byKey[it] }
          ?: variants.firstNotNullOfOrNull { k ->
            device?.let { DeviceMatch.pick(designs.deviceByKey[k].orEmpty(), it) }
          }
          ?: variants.firstNotNullOfOrNull { designs.byKey[it] }
      bytes?.let {
        WearSlotDesign(cardKey, container, ee.schimke.ha.client.design.Sha256.hex(it)) to it
      }
    }
  }
}

/**
 * The cards the watch can place as Wear widgets, from what the phone shows on it: [pinned], each
 * pinned card by its pin's `cardKey` (the ones [wearCardDesigns] picks designs for), and [all],
 * those and the pinned sections' cards (the ones whose pictures the watch needs).
 */
internal data class WearWidgetCards(
  val pinned: List<Pair<String, CardConfig>>,
  val all: List<CardConfig>,
)

/** [WearWidgetCards] of the phone's pins: cards that don't decode are left out. */
internal fun wearWidgetCards(
  cards: List<MobilePinnedCard>,
  sections: List<MobilePinnedSection>,
): WearWidgetCards {
  val pinned = cards.mapNotNull { pin ->
    pin.card.rawJson.toCardConfigOrNull()?.let { pin.key to it }
  }
  val inSections = sections.flatMap { section ->
    section.cards.mapNotNull { it.rawJson.toCardConfigOrNull() }
  }
  return WearWidgetCards(pinned, (pinned.map { it.second } + inSections).distinct())
}
