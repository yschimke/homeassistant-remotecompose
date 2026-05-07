package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.components.HaCalendarData
import ee.schimke.ha.rc.components.HaCalendarEvent
import ee.schimke.ha.rc.components.RemoteHaCalendar
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * `calendar` card. HA carries event data via the calendar API rather
 * than entity attributes; the converter checks each calendar entity's
 * `events` attribute (some integrations expose it) and otherwise falls
 * back to a "no upcoming events" stub. Hosts that hydrate events at
 * capture time can extend [HaSnapshot] later; the model accepts any
 * pre-built event list so adapters can plug in.
 */
class CalendarCardConverter : CardConverter {
    override val cardType: String = CardTypes.CALENDAR

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 220

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val ids = entityIds(card)
        val title = card.raw["title"]?.jsonPrimitive?.content
            ?: ids.firstOrNull()
                ?.let { snapshot.states[it]?.attributes?.get("friendly_name")?.jsonPrimitive?.content }
            ?: "Calendar"
        val initialDays = card.raw["initial_view"]?.jsonPrimitive?.content?.let { view ->
            when (view) {
                "dayGridMonth" -> 28
                "dayGridDay" -> 1
                else -> 7
            }
        } ?: 7

        val events = ids.flatMap { id ->
            val arr = (snapshot.states[id]?.attributes?.get("events") as? JsonArray)
                ?: JsonArray(emptyList())
            arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val summary = obj["summary"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val start = obj["start"]?.jsonPrimitive?.content ?: ""
                HaCalendarEvent(
                    whenLabel = formatStart(start).rs,
                    summary = summary.rs,
                    accent = calendarColor(id),
                )
            }
        }.take(8)

        RemoteHaCalendar(
            HaCalendarData(
                title = title.rs,
                rangeLabel = "Next ${initialDays} days".rs,
                events = events,
            ),
            modifier = modifier,
        )
    }
}

private fun entityIds(card: CardConfig): List<String> {
    val arr: JsonArray = card.raw["entities"]?.jsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        when (el) {
            is JsonPrimitive -> el.content
            is JsonObject -> el["entity"]?.jsonPrimitive?.content
            else -> null
        }
    }
}

/** Best-effort: take the date portion of an ISO datetime, optionally
 *  appending the HH:mm if present. Robust to the all-day form which
 *  emits just the date. */
private fun formatStart(start: String): String {
    if (start.isBlank()) return ""
    val datePart = start.substringBefore('T')
    val timePart = start.substringAfter('T', "").substringBefore(':')
    val minuteOf = if (start.contains(':')) start.substringAfter(':', "").take(2) else ""
    return if (timePart.isNotEmpty() && minuteOf.isNotEmpty()) "$datePart $timePart:$minuteOf"
    else datePart
}

private fun calendarColor(entityId: String): Color {
    // Stable colour per calendar so visually grouped events share a dot.
    val hash = entityId.hashCode()
    val palette = listOf(
        Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFF6A1B9A),
        Color(0xFFC62828), Color(0xFFE65100), Color(0xFF00838F),
    )
    return palette[((hash % palette.size) + palette.size) % palette.size]
}
