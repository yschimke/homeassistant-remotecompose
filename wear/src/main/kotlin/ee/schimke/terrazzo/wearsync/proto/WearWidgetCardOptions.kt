package ee.schimke.terrazzo.wearsync.proto

/**
 * The cards a Wear widget can show, and the keys that name them. Shared by the phone, which chooses
 * each widget's card ([WearWidgetCards]), and the watch, which finds the newest copy of a chosen
 * card by its key. Duplicated in the `app` and `wear` modules like [WearSyncPaths]: both copies
 * must stay identical, or a choice names a card the watch can't find.
 */

/** A card a widget can show: one the phone sent to the watch, by a key that names it. */
data class WearWidgetCardOption(val cardKey: String, val card: CardSummary)

/**
 * The cards a widget can show, in the watch's top-level order: the pinned cards and the pinned
 * sections' cards, interleaved by `orderIndex` (ties by key), without repeats (a card pinned on its
 * own and in a section is listed once, under its pin's key).
 */
fun wearWidgetCardOptions(
  pinned: PinnedCardSet?,
  sections: PinnedSectionSet?,
): List<WearWidgetCardOption> {
  val cards =
    pinned?.cards.orEmpty().map { card ->
      Triple(
        card.orderIndex,
        "card:${card.cardKey.ifEmpty { card.card.primaryEntityId }}",
        listOf(
          WearWidgetCardOption(
            card.cardKey.ifEmpty { "card:${digest(card.card.rawJson)}" },
            card.card,
          )
        ),
      )
    }
  val inSections =
    sections?.sections.orEmpty().map { section ->
      Triple(
        section.orderIndex,
        "section:${section.sectionKey.ifEmpty { section.title }}",
        section.cards.map { WearWidgetCardOption(wearSectionCardKey(section, it), it) },
      )
    }
  val ordered = (cards + inSections).sortedWith(compareBy({ it.first }, { it.second }))
  // Pinned cards first, then section cards: a card in both keeps its pin's key.
  val topLevel = ordered.filter { it.second.startsWith("card:") }.flatMap { it.third }
  val sectionCards = ordered.filter { it.second.startsWith("section:") }.flatMap { it.third }
  return (topLevel + sectionCards).distinctBy { it.card }
}

/**
 * The [WearWidgetCardOption.cardKey] of [card] in [section]. Section cards have no key of their
 * own, so it is the section's and the card's config: the same card in the same section keeps its
 * key across syncs.
 */
fun wearSectionCardKey(section: PinnedSection, card: CardSummary): String =
  "section:${section.sectionKey.ifEmpty { section.title }}:${digest(card.rawJson)}"

/**
 * Card types that only fill a Small widget: at the Large container (200×112 dp) they would draw
 * their small tier in a lot of empty space, because what they show (forecasts, history, lists)
 * doesn't travel to the watch. A Large widget isn't offered them.
 */
val WEAR_SMALL_ONLY_CARD_TYPES: Set<String> =
  setOf(
    "heading",
    "markdown",
    "clock",
    "weather-forecast",
    "logbook",
    "history-graph",
    "statistics-graph",
    "todo-list",
    "calendar",
  )

/** The options a widget of the given size offers: a Large one leaves out the small-only cards. */
fun List<WearWidgetCardOption>.forWearWidget(large: Boolean): List<WearWidgetCardOption> =
  if (large) filter { it.card.type !in WEAR_SMALL_ONLY_CARD_TYPES } else this

private fun digest(text: String): String =
  java.security.MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray())
    .take(8)
    .joinToString("") { "%02x".format(it) }
