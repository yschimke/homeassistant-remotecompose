package ee.schimke.terrazzo.shared

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardKey
import ee.schimke.ha.model.View
import kotlinx.serialization.json.*

/** A heading and its content travel together; unrelated tall cards never reserve a whole row. */
internal data class DashboardGroup(
  val id: String,
  val title: String?,
  val cards: List<Pair<CardKey, CardConfig>>,
)

internal fun dashboardGroups(view: View, path: String?, viewIndex: Int): List<DashboardGroup> =
  buildList {
    val viewPath = view.path ?: "view-$viewIndex"
    fun partition(cards: List<CardConfig>, section: Int?, title: String?) {
      var heading = title
      var batch = mutableListOf<Pair<CardKey, CardConfig>>()
      var ordinal = 0
      fun flush() {
        if (batch.isNotEmpty()) {
          add(DashboardGroup("${section ?: "cards"}-${ordinal++}", heading, batch.toList()))
          batch = mutableListOf()
        }
      }
      val grouped = section != null || cards.any { it.type == "heading" }
      cards.forEachIndexed { index, card ->
        val label = (card.raw["heading"] as? JsonPrimitive)?.contentOrNull
        // Keep actionable headings in the content, so grouping cannot drop a control.
        if (
          card.type == "heading" &&
            label != null &&
            card.raw["tap_action"] == null &&
            card.raw["entity"] == null
        ) {
          flush()
          heading = label
        } else {
          batch += CardKey(path, viewPath, section, index, card.type) to card
          if (!grouped) flush()
        }
      }
      flush()
    }
    partition(view.cards, null, null)
    view.sections.forEachIndexed { index, section ->
      partition(section.cards, index, section.title)
    }
  }

internal fun dashboardColumnCount(width: Float, maxColumns: Int?): Int =
  ((width + 16) / 360).toInt().coerceAtLeast(1).coerceAtMost((maxColumns ?: 4).coerceIn(1, 6))

internal fun childCards(card: CardConfig): List<CardConfig> =
  (card.raw["cards"] as? JsonArray)
    ?.mapNotNull { raw ->
      val obj = raw as? JsonObject ?: return@mapNotNull null
      val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
      CardConfig(type, obj)
    }
    .orEmpty()
