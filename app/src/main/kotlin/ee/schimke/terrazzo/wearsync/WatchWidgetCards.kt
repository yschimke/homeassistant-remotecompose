package ee.schimke.terrazzo.wearsync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ee.schimke.terrazzo.core.wearsync.WatchWidget
import ee.schimke.terrazzo.core.wearsync.WatchWidgetCardOption
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.WearPlacedWidget
import ee.schimke.terrazzo.wearsync.proto.WearPlacedWidgets
import ee.schimke.terrazzo.wearsync.proto.WearWidgetCardChoice
import ee.schimke.terrazzo.wearsync.proto.WearWidgetCardOption
import ee.schimke.terrazzo.wearsync.proto.forWearWidget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The card the user chose for each watch widget, by [watchWidgetKey]. Only what was chosen on the
 * phone: a widget with no row shows what the watch gave it when it was placed, or the first card it
 * can show ([watchWidgetCardChoices]).
 */
internal class WatchWidgetChoiceStore(private val context: Context) {

  /** The chosen card key per [watchWidgetKey]. */
  val choices: Flow<Map<String, String>>
    get() =
      context.store.data.map { prefs ->
        prefs.asMap().entries.associate { (key, value) -> key.name to value.toString() }
      }

  suspend fun choose(nodeId: String, instanceId: Int, cardKey: String) {
    context.store.edit { it[stringPreferencesKey(watchWidgetKey(nodeId, instanceId))] = cardKey }
  }

  /** Forgets every choice not in [liveKeys]: the widget was removed, or its watch unpaired. */
  suspend fun retainOnly(liveKeys: Set<String>) {
    context.store.edit { prefs ->
      prefs.asMap().keys.filter { it.name !in liveKeys }.forEach { prefs.remove(it) }
    }
  }

  private companion object {
    val Context.store by preferencesDataStore(name = "terrazzo_watch_widgets")
  }
}

/** A watch widget's key in [WatchWidgetChoiceStore]: instance ids are only unique per watch. */
internal fun watchWidgetKey(nodeId: String, instanceId: Int): String = "$nodeId/$instanceId"

/**
 * The card each placed widget shows, for `/wear/widget-cards`: the phone's choice when it is still
 * a card the widget can show, else the one the watch reports it showing (a choice carried over from
 * the watch, or the default it gave a new widget), else the first card it can show. A widget that
 * can show none is left out, and keeps what it has.
 *
 * @param placed each watch's [WearPlacedWidgets], by node id.
 * @param chosen the phone's choices, by [watchWidgetKey].
 */
internal fun watchWidgetCardChoices(
  placed: Map<String, WearPlacedWidgets>,
  chosen: Map<String, String>,
  options: List<WearWidgetCardOption>,
): List<WearWidgetCardChoice> =
  placed.entries
    .sortedBy { it.key }
    .flatMap { (nodeId, widgets) ->
      widgets.widgets.mapNotNull { widget ->
        val option = cardFor(nodeId, widget, chosen, options) ?: return@mapNotNull null
        WearWidgetCardChoice(nodeId, widget.instanceId, option.cardKey, option.card)
      }
    }

/** The phone's list of [placed] widgets, each with its card ([watchWidgetCardChoices]). */
internal fun watchWidgets(
  placed: Map<String, WearPlacedWidgets>,
  chosen: Map<String, String>,
  options: List<WearWidgetCardOption>,
): List<WatchWidget> =
  placed.entries
    .sortedBy { it.key }
    .flatMap { (nodeId, widgets) ->
      widgets.widgets.map { widget ->
        WatchWidget(
          nodeId = nodeId,
          nodeName = widgets.nodeName,
          instanceId = widget.instanceId,
          large = widget.large,
          cardKey = cardFor(nodeId, widget, chosen, options)?.cardKey,
          options =
            options.forWearWidget(widget.large).map {
              WatchWidgetCardOption(it.cardKey, it.card.label())
            },
        )
      }
    }

private fun cardFor(
  nodeId: String,
  widget: WearPlacedWidget,
  chosen: Map<String, String>,
  options: List<WearWidgetCardOption>,
): WearWidgetCardOption? {
  val fitting = options.forWearWidget(widget.large)
  return listOfNotNull(chosen[watchWidgetKey(nodeId, widget.instanceId)], widget.cardKey)
    .firstNotNullOfOrNull { key -> fitting.firstOrNull { it.cardKey == key } }
    ?: fitting.firstOrNull()
}

/** What a list names [this] card by: its title, else its entity, else its type. */
private fun CardSummary.label(): String =
  title.ifEmpty { primaryEntityId }.ifEmpty { type }.ifEmpty { "Card" }
