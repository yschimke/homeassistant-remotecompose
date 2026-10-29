@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import ee.schimke.ha.rc.components.RemoteHaCalendar
import ee.schimke.ha.rc.components.RemoteHaCalendarIdentity
import ee.schimke.ha.rc.components.RemoteHaLogbook
import ee.schimke.ha.rc.components.RemoteHaLogbookIdentity
import ee.schimke.ha.rc.components.RemoteHaSummaryLine
import ee.schimke.ha.rc.components.RemoteHaTodoList
import ee.schimke.ha.rc.components.RemoteHaTodoListIdentity
import ee.schimke.ha.rc.components.cardText
import ee.schimke.ha.rc.components.logbookSummaryText
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The parts the list cards' designs arrange (card-designs/ui-builder/designs/`logbook.uid`,
 * `calendar.uid` and `todo-list.uid`): each draws one tier of the card being drawn
 * ([LocalCardInput]), whichever of the three it is; which tier a surface gets is the design's
 * `card-size-mode` and `size-tiers` (the bulk cards' single gate, #224). The card design renderer
 * draws them through [components]; ha-cards-catalog's `HaBulkCard*` wrappers put the same calls on
 * UI Builder's shelf.
 */
object BulkDesignParts {
  /** The card being drawn, or a to-do list in UI Builder's canvas. */
  @Composable
  private fun input(): DesignTemplateInput =
    LocalCardInput.current
      ?: DesignTemplateInput(
        CardConfig(
          CardTypes.TODO_LIST,
          JsonObject(
            mapOf(
              "type" to JsonPrimitive(CardTypes.TODO_LIST),
              "entity" to JsonPrimitive("todo.shopping_list"),
            )
          ),
        ),
        HaSnapshot(),
      )

  /**
   * `ha-cards/bulk-card-full`: the whole list; [compactRows] keeps a to-do list to a Wear Large
   * container's title over two short rows.
   */
  @Composable
  fun Full(compactRows: Boolean, modifier: RemoteModifier) {
    val (card, snapshot) = input()
    when (card.type) {
      CardTypes.LOGBOOK -> {
        val data = logbookData(card, snapshot)
        val header = if (data.title != null) LIST_TITLED_DP else LIST_PADDING_DP
        val rows = launcherRows(header, LOGBOOK_ROW_DP) ?: data.entries.size
        RemoteHaLogbook(data.copy(entries = data.entries.take(rows)), modifier)
      }
      CardTypes.CALENDAR -> {
        val data = calendarData(card, snapshot)
        val rows = launcherRows(LIST_TITLED_DP, CALENDAR_ROW_DP) ?: data.events.size
        RemoteHaCalendar(data.copy(events = data.events.take(rows)), modifier)
      }
      else ->
        RemoteHaTodoList(
          todoData(card, snapshot),
          modifier,
          compactRows = if (compactRows) WEAR_LARGE_TODO_ROWS else null,
        )
    }
  }

  /**
   * `ha-cards/bulk-card-identity`: the card's identity in a narrow cell: the latest entry, the next
   * event, or how many are left.
   */
  @Composable
  fun Identity(modifier: RemoteModifier) {
    val (card, snapshot) = input()
    // One launcher column takes each identity's narrow form.
    val narrow = (LocalWidgetCaptureSizeDp.current?.widthDp ?: Int.MAX_VALUE) < NARROW_IDENTITY_DP
    when (card.type) {
      CardTypes.LOGBOOK -> RemoteHaLogbookIdentity(logbookData(card, snapshot), modifier, narrow)
      CardTypes.CALENDAR -> RemoteHaCalendarIdentity(calendarData(card, snapshot), modifier, narrow)
      else -> RemoteHaTodoListIdentity(todoData(card, snapshot), modifier, narrow)
    }
  }

  /**
   * `ha-cards/bulk-card-summary`: the card's title over its summary line, for a Wear Small slot.
   */
  @Composable
  fun Summary() {
    val (card, snapshot) = input()
    if (card.type == CardTypes.LOGBOOK) {
      // The latest row's own bindings, so the line follows its entity as the full card does.
      val data = logbookData(card, snapshot)
      RemoteHaSummaryLine(cardText("title", data.title ?: "Logbook"), logbookSummaryText(data))
      return
    }
    val (title, summary) =
      when (card.type) {
        CardTypes.CALENDAR -> calendarData(card, snapshot).let { it.title to calendarSummary(it) }
        else -> todoData(card, snapshot).let { it.title to todoSummary(it) }
      }
    RemoteHaSummaryLine(cardText("title", title), cardText("summary", summary))
  }

  /**
   * How many [rowDp] rows a launcher widget holds under a [headerDp] header (at least one), so a
   * list shows the entries that fit rather than cutting the last in half at the widget's bottom
   * edge. Null off a launcher capture.
   */
  @Composable
  private fun launcherRows(headerDp: Int, rowDp: Int): Int? =
    LocalWidgetCaptureSizeDp.current?.let { ((it.heightDp - headerDp) / rowDp).coerceAtLeast(1) }

  // A list card's vertical padding (12 dp each side), and that with its Title line and the 8 dp
  // gap under it; a logbook entry (a Body name over a Label message) and a calendar event (a
  // Caption date over a Body summary), each with the gap after it.
  private const val LIST_PADDING_DP = 24
  private const val LIST_TITLED_DP = 56
  private const val LOGBOOK_ROW_DP = 45
  private const val CALENDAR_ROW_DP = 48

  /** Below this width (one launcher column) an identity takes its narrow form. */
  private const val NARROW_IDENTITY_DP = 100

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/bulk-card-full" to { node, modifier -> Full(node.bool("compactRows"), modifier) },
      "ha-cards/bulk-card-identity" to { _, modifier -> Identity(modifier) },
      "ha-cards/bulk-card-summary" to { _, _ -> Summary() },
    )
}
