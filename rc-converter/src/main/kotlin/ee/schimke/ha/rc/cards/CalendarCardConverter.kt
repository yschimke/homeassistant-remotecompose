package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardEntityIds
import ee.schimke.ha.rc.components.HaCalendarData
import ee.schimke.ha.rc.components.HaCalendarEvent
import ee.schimke.ha.rc.components.RemoteHaCalendar
import ee.schimke.ha.rc.components.RemoteHaCalendarIdentity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `calendar` card. HA carries event data via the calendar API rather than entity attributes; the
 * converter checks each calendar entity's `events` attribute (some integrations expose it) and
 * otherwise falls back to a "no upcoming events" stub. Hosts that hydrate events at capture time
 * can extend [HaSnapshot] later; the model accepts any pre-built event list so adapters can plug
 * in.
 */
class CalendarCardConverter : CardConverter {
  override val cardType: String = CardTypes.CALENDAR

  // Baked, non-bindable content (see CardConverter.dataSignature):
  // re-encode when any referenced entity's snapshot data moves.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    cardDataSignature(cardEntityIds(card), snapshot)

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 220

  /**
   * A calendar renders from a card design: per event (at most eight, in calendar order), which
   * calendar it belongs to; per calendar, its dot colour. Titles, the range label and each event's
   * time and summary are bindings (`card.title`, `card.range`, `card.row.<i>.*`).
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!setOf("type", "title", "entities", "initial_view").containsAll(card.raw.keys)) return null
    val ids = entityIds(card)
    if (ids.isEmpty() || ids.any { it !in snapshot.states }) return null
    val events = calendarEvents(ids, snapshot)
    return DesignProperties.build {
      enum(EntityDesign.COUNT, ids.size)
      ids.forEachIndexed { k, id -> enum("c$k.color", colorIndex(id, snapshot.states[id])) }
      enum("events", events.size)
      events.forEachIndexed { i, (k, _) -> enum("e$i.cal", k) }
    }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    val count = properties[EntityDesign.COUNT]?.toIntOrNull() ?: return null
    val eventCount = properties["events"]?.toIntOrNull() ?: return null
    val owners = (0 until eventCount).map { properties["e$it.cal"]?.toIntOrNull() ?: return null }
    val entities =
      (0 until count).map { k ->
        val slot = CardDesignSlots.slotId("calendar", k)
        val events =
          kotlinx.serialization.json.buildJsonArray {
            owners
              .withIndex()
              .filter { it.value == k }
              .forEach { (i, _) ->
                addJsonObject {
                  put("summary", "Event $i")
                  put("start", "2000-01-01T00:00:00")
                }
              }
          }
        CardDesignSlots.templateEntity(
            slot,
            placeholderState = "off",
            attributes = mapOf(COLOR_INDEX to (properties["c$k.color"] ?: "0")),
          )
          .let {
            it.copy(
              attributes =
                kotlinx.serialization.json.JsonObject(it.attributes + ("events" to events))
            )
          }
      }
    val card =
      CardConfig(
        type = cardType,
        raw =
          buildJsonObject {
            put("type", cardType)
            put("title", "Title")
            putJsonArray("entities") { entities.forEach { add(it.entityId) } }
          },
      )
    return DesignTemplateInput(card, HaSnapshot(states = entities.associateBy { it.entityId }))
  }

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    val data = calendarData(card, snapshot)
    return CardSnapshotBindings(
      buildMap {
        put("card.title", data.title)
        put("card.range", data.rangeLabel)
        data.events.forEachIndexed { i, event ->
          put("card.row.$i.when", event.whenLabel)
          put("card.row.$i.summary", event.summary)
        }
      },
      emptyMap(),
      emptyMap(),
      emptyMap(),
    )
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val data = calendarData(card, snapshot)

    when (LocalCardSizeMode.current) {
      CardSizeMode.Wrap -> RemoteHaCalendar(data, modifier = modifier)
      // Narrow → next-event identity; wider → the full agenda.
      // Single width gate (#224).
      CardSizeMode.Fixed ->
        RemoteSizeBreakpoint(
          thresholdsDp = intArrayOf(BULK_IDENTITY_THRESHOLD_DP),
          modifier = modifier,
          axis = BreakpointAxis.Width,
        ) { tier ->
          if (tier == 0) {
            RemoteHaCalendarIdentity(data, RemoteModifier.fillMaxWidth())
          } else {
            RemoteHaCalendar(data, RemoteModifier.fillMaxWidth())
          }
        }
    }
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

/**
 * Best-effort: take the date portion of an ISO datetime, optionally appending the HH:mm if present.
 * Robust to the all-day form which emits just the date.
 */
private fun formatStart(start: String): String {
  if (start.isBlank()) return ""
  val datePart = start.substringBefore('T')
  val timePart = start.substringAfter('T', "").substringBefore(':')
  val minuteOf = if (start.contains(':')) start.substringAfter(':', "").take(2) else ""
  return if (timePart.isNotEmpty() && minuteOf.isNotEmpty()) "$datePart $timePart:$minuteOf"
  else datePart
}

private val CalendarPalette =
  listOf(
    Color(0xFF1565C0),
    Color(0xFF2E7D32),
    Color(0xFF6A1B9A),
    Color(0xFFC62828),
    Color(0xFFE65100),
    Color(0xFF00838F),
  )

/**
 * Stable colour per calendar so visually grouped events share a dot: from the entity id, or from a
 * design template's placeholder `color_index`.
 */
private fun colorIndex(entityId: String, entity: ee.schimke.ha.model.EntityState?): Int =
  entity?.attributes?.get(COLOR_INDEX)?.jsonPrimitive?.content?.toIntOrNull()
    ?: entityId.hashCode().let {
      ((it % CalendarPalette.size) + CalendarPalette.size) % CalendarPalette.size
    }

private fun calendarColor(entityId: String, entity: ee.schimke.ha.model.EntityState?): Color {
  return CalendarPalette[colorIndex(entityId, entity).coerceIn(CalendarPalette.indices)]
}

/** The calendar's data for [card] and [snapshot]: what it draws, and what a template binds. */
private fun calendarData(card: CardConfig, snapshot: HaSnapshot): HaCalendarData {
  val ids = entityIds(card)
  val title =
    card.raw["title"]?.jsonPrimitive?.content
      ?: ids.firstOrNull()?.let {
        snapshot.states[it]?.attributes?.get("friendly_name")?.jsonPrimitive?.content
      }
      ?: "Calendar"
  val initialDays =
    card.raw["initial_view"]?.jsonPrimitive?.content?.let { view ->
      when (view) {
        "dayGridMonth" -> 28
        "dayGridDay" -> 1
        else -> 7
      }
    } ?: 7

  val events =
    calendarEvents(ids, snapshot).map { (k, obj) ->
      val id = ids[k]
      HaCalendarEvent(
        whenLabel = formatStart(obj["start"]?.jsonPrimitive?.content ?: ""),
        summary = obj["summary"]?.jsonPrimitive?.content.orEmpty(),
        accent = calendarColor(id, snapshot.states[id]),
      )
    }
  return HaCalendarData(title = title, rangeLabel = "Next ${initialDays} days", events = events)
}

/** Each event shown, with the index of the calendar it comes from: calendar order, at most 8. */
private fun calendarEvents(
  ids: List<String>,
  snapshot: HaSnapshot,
): List<Pair<Int, JsonObject>> =
  ids
    .flatMapIndexed { k, id ->
      ((snapshot.states[id]?.attributes?.get("events") as? JsonArray) ?: JsonArray(emptyList()))
        .mapNotNull { el ->
          val obj = el as? JsonObject ?: return@mapNotNull null
          if (obj["summary"]?.jsonPrimitive?.content == null) return@mapNotNull null
          k to obj
        }
    }
    .take(8)

/** Placeholder attribute a design template's calendar carries for its dot colour. */
private const val COLOR_INDEX = "color_index"
