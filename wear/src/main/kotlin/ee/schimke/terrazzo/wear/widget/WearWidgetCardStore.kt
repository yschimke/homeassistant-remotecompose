package ee.schimke.terrazzo.wear.widget

import android.content.Context
import ee.schimke.terrazzo.wear.sync.atomicWriteBytes
import ee.schimke.terrazzo.wear.sync.readBytesOrNull
import ee.schimke.terrazzo.wear.ui.TopLevelEntry
import ee.schimke.terrazzo.wear.ui.mergeOrdered
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSection
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import java.io.File
import kotlinx.serialization.Serializable

/**
 * The card a placed Wear widget shows, chosen on the watch ([WearWidgetConfigActivity], or the Wear
 * app's Widgets screen). Watch-local: never sent over the Data Layer.
 *
 * [card] is a copy of the card as it was chosen, so the widget keeps drawing it when the phone
 * later unpins it; while the phone still sends a card under [cardKey], that newer copy wins
 * ([currentWidgetCard]).
 */
@Serializable
data class WearWidgetCard(
  /** The card's [WidgetCardOption.cardKey]: a pinned card's `cardKey`, or a section card's. */
  val cardKey: String = "",
  val card: CardSummary = CardSummary(),
  val chosenAtMs: Long = 0L,
)

/**
 * Which card each placed Wear widget shows, by its Glance Wear widget instance id
 * ([androidx.glance.wear.core.WidgetInstanceId.id]).
 *
 * Kept beside [ee.schimke.terrazzo.wear.sync.WearOfflineStore]'s files, one proto file per widget
 * under `filesDir/terrazzo/wear/widgets/<instanceId>.pb`, written tmp+rename like the rest, so a
 * widget render and a choice never see half a file.
 */
class WearWidgetCardStore(context: Context) {
  private val dir: File = File(context.filesDir, "terrazzo/wear/widgets").apply { mkdirs() }

  /** The card widget [instanceId] shows, or null when none was chosen. */
  fun read(instanceId: Int): WearWidgetCard? =
    file(instanceId).readBytesOrNull()?.let { decodeProto<WearWidgetCard>(it) }

  /** Every chosen card, by widget instance id. */
  fun readAll(): Map<Int, WearWidgetCard> =
    dir
      .listFiles { f -> f.isFile && f.name.endsWith(".pb") }
      .orEmpty()
      .mapNotNull { f ->
        val id = f.nameWithoutExtension.toIntOrNull() ?: return@mapNotNull null
        f.readBytesOrNull()?.let { decodeProto<WearWidgetCard>(it) }?.let { id to it }
      }
      .toMap()

  fun write(instanceId: Int, value: WearWidgetCard) {
    file(instanceId).atomicWriteBytes(encodeProto(value))
  }

  /** Forgets widget [instanceId]'s card: it was removed from the watch. */
  fun remove(instanceId: Int) {
    file(instanceId).delete()
  }

  /** Forgets every widget's card: the phone signed out, so none of its cards may stay. */
  fun clear() {
    dir.listFiles().orEmpty().forEach { it.delete() }
  }

  private fun file(instanceId: Int): File = File(dir, "$instanceId.pb")
}

/** A card a widget can show: one the phone sent to the watch, by a key that names it. */
data class WidgetCardOption(val cardKey: String, val card: CardSummary)

/**
 * The cards a widget can show, as the picker lists them: the pinned cards in the top-level list's
 * order, then the cards of the pinned sections, without repeats (a card pinned on its own and in a
 * section is listed once, under its pin's key). The same cards, in the same order, as
 * [ee.schimke.terrazzo.wear.sync.warmupCards].
 */
internal fun widgetCardOptions(
  pinned: PinnedCardSet?,
  sections: PinnedSectionSet?,
): List<WidgetCardOption> {
  val entries = mergeOrdered(pinned?.cards.orEmpty(), sections?.sections.orEmpty())
  val topLevel =
    entries.filterIsInstance<TopLevelEntry.Card>().map {
      WidgetCardOption(
        it.card.cardKey.ifEmpty { "card:${digest(it.card.card.rawJson)}" },
        it.card.card,
      )
    }
  val inSections =
    entries.filterIsInstance<TopLevelEntry.Section>().flatMap { entry ->
      entry.section.cards.map { sectionCardOption(entry.section, it) }
    }
  return (topLevel + inSections).distinctBy { it.card }
}

/**
 * The [options] a widget of the given size offers. A Large widget leaves out the cards that only
 * fill a Small one ([WearCardDataTier.SmallOnly]): at 200×112 dp they would draw their small tier
 * in a lot of empty space, so they aren't presented as a Large choice at all.
 */
internal fun List<WidgetCardOption>.forWidget(large: Boolean): List<WidgetCardOption> =
  if (large) filter { wearCardDataTier(it.card.type).supportsLarge } else this

/**
 * A section card's option. Section cards have no key of their own, so it is the section's and the
 * card's config: the same card in the same section keeps its key across syncs.
 */
private fun sectionCardOption(section: PinnedSection, card: CardSummary): WidgetCardOption =
  WidgetCardOption(sectionCardKey(section, card), card)

/** The [WidgetCardOption.cardKey] of [card] in [section]: see [sectionCardOption]. */
internal fun sectionCardKey(section: PinnedSection, card: CardSummary): String =
  "section:${section.sectionKey.ifEmpty { section.title }}:${digest(card.rawJson)}"

/**
 * What a widget that chose [choice] draws: the card the phone sends under its key now, or the copy
 * kept when the card was chosen, once the phone no longer sends it.
 */
internal fun currentWidgetCard(
  choice: WearWidgetCard,
  pinned: PinnedCardSet?,
  sections: PinnedSectionSet?,
): CardSummary =
  widgetCardOptions(pinned, sections).firstOrNull { it.cardKey == choice.cardKey }?.card
    ?: choice.card

private fun digest(text: String): String =
  java.security.MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray())
    .take(8)
    .joinToString("") { "%02x".format(it) }
