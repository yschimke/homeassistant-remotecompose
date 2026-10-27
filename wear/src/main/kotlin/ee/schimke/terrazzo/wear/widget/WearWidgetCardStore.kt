package ee.schimke.terrazzo.wear.widget

import android.content.Context
import ee.schimke.terrazzo.wear.sync.atomicWriteBytes
import ee.schimke.terrazzo.wear.sync.readBytesOrNull
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSection
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import ee.schimke.terrazzo.wearsync.proto.WearWidgetCardOption
import ee.schimke.terrazzo.wearsync.proto.WearWidgetCards
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import ee.schimke.terrazzo.wearsync.proto.forWearWidget
import ee.schimke.terrazzo.wearsync.proto.wearSectionCardKey
import ee.schimke.terrazzo.wearsync.proto.wearWidgetCardOptions
import java.io.File
import kotlinx.serialization.Serializable

/**
 * The card a placed Wear widget shows, chosen on the phone and taken from its [WearWidgetCards]
 * ([applyWidgetCards]). The watch keeps its own copy so a widget renders without the phone.
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

  /**
   * Forgets the card of every widget not in [placedIds], returning how many were dropped.
   *
   * [placedIds] is what the system says is placed ([placedCardWidgetsOrNull]). A widget removed
   * while the app wasn't running, or dropped with the host's data, never reaches
   * [CardWidget.onRemoved]; without this its file stays forever. A card chosen within [graceMs] of
   * [nowMs] is kept even when not yet listed: the host may list a widget a moment after the phone's
   * choice for it arrived.
   */
  fun retainOnly(
    placedIds: Set<Int>,
    nowMs: Long = System.currentTimeMillis(),
    graceMs: Long = RECENT_CHOICE_GRACE_MS,
  ): Int {
    var dropped = 0
    dir
      .listFiles { f -> f.isFile && f.name.endsWith(".pb") }
      .orEmpty()
      .forEach { f ->
        val id = f.nameWithoutExtension.toIntOrNull()
        if (id != null && id in placedIds) return@forEach
        val chosenAt =
          f.readBytesOrNull()
            ?.let { runCatching { decodeProto<WearWidgetCard>(it) }.getOrNull() }
            ?.chosenAtMs ?: 0L
        if (id != null && nowMs - chosenAt in 0 until graceMs) return@forEach
        if (f.delete()) dropped++
      }
    return dropped
  }

  /** Forgets every widget's card: the phone signed out, so none of its cards may stay. */
  fun clear() {
    dir.listFiles().orEmpty().forEach { it.delete() }
  }

  private fun file(instanceId: Int): File = File(dir, "$instanceId.pb")

  companion object {
    /** How long a just-chosen card survives [retainOnly] before the host lists its widget. */
    const val RECENT_CHOICE_GRACE_MS: Long = 60_000L
  }
}

/** A card a widget can show: see [WearWidgetCardOption]. */
typealias WidgetCardOption = WearWidgetCardOption

/** The cards a widget can show, as the phone offers them: see [wearWidgetCardOptions]. */
internal fun widgetCardOptions(
  pinned: PinnedCardSet?,
  sections: PinnedSectionSet?,
): List<WidgetCardOption> = wearWidgetCardOptions(pinned, sections)

/** The [options] a widget of the given size offers: see [forWearWidget]. */
internal fun List<WidgetCardOption>.forWidget(large: Boolean): List<WidgetCardOption> =
  forWearWidget(large)

/** The [WidgetCardOption.cardKey] of [card] in [section]: see [wearSectionCardKey]. */
internal fun sectionCardKey(section: PinnedSection, card: CardSummary): String =
  wearSectionCardKey(section, card)

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
