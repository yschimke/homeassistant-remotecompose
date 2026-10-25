package ee.schimke.terrazzo.wear.ui

import ee.schimke.ha.model.MESSAGE_CARD_TYPE
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet

/**
 * The cards [WearCardScreen] pages through: the cards of the pinned section [sectionKey], or the
 * pinned list's own cards in its order when null. A message card is an input form, not a card to
 * look at, so it isn't drawn full screen.
 */
internal fun fullScreenDeck(
  sectionKey: String?,
  pinned: PinnedCardSet,
  sections: PinnedSectionSet,
): List<CardSummary> {
  val cards =
    if (sectionKey != null) {
      sections.sections.firstOrNull { it.sectionKey == sectionKey }?.cards.orEmpty()
    } else {
      mergeOrdered(pinned.cards, emptyList()).map { (it as TopLevelEntry.Card).card.card }
    }
  return cards.filter { it.type != MESSAGE_CARD_TYPE }
}

/** Whether [card] opens full screen: see [fullScreenDeck]. */
internal fun opensFullScreen(card: CardSummary): Boolean = card.type != MESSAGE_CARD_TYPE

/**
 * Names [card] within its deck, across a sync that leaves its config alone and a process restart.
 */
internal fun fullScreenCardKey(card: CardSummary): String = card.rawJson.hashCode().toString(16)
