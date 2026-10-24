package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HistoryPoint
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.icons.HaIconMap
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a `more-info` tap opens on the dashboard: a half-height sheet led by the entity's icon, name
 * and live value, then its history over a chosen range — a line chart for a numeric entity
 * (sensors), an on/off timeline for anything else (switches, binary sensors, lights) — with a
 * summary under it, then the entity's attributes.
 *
 * Everything is drawn from the app theme's roles, so the sheet re-skins with the chosen palette;
 * the entity's accent (the icon badge, the chart line, the timeline's active runs) is its fixed
 * state colour ([HaStateColor]), as the dashboard's own tiles draw it.
 *
 * The value and attributes read the live snapshot, and the latest state is appended to the fetched
 * history, so the chart keeps up while the sheet is open. [loadHistory] fetches the entity's
 * recorder history over the last given hours. [onOpenHistory] (null when there is no Home Assistant
 * to open, as in demo mode) opens the entity's history in the browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityMoreInfoSheet(
  entityId: String,
  state: EntityState?,
  loadHistory: suspend (hours: Int) -> List<HistoryPoint>,
  onOpenHistory: (() -> Unit)?,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState()
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    EntityMoreInfo(entityId, state, loadHistory, onOpenHistory)
  }
}

/**
 * The sheet's content, apart from the sheet so a preview can draw it. [initialHistory] seeds the
 * default range's history so a screenshot shows the settled chart rather than the loading state;
 * [nowMs] is the end of the window, a frozen clock in previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityMoreInfo(
  entityId: String,
  state: EntityState?,
  loadHistory: suspend (hours: Int) -> List<HistoryPoint>,
  onOpenHistory: (() -> Unit)?,
  modifier: Modifier = Modifier,
  initialHistory: List<HistoryPoint>? = null,
  nowMs: () -> Long = System::currentTimeMillis,
) {
  val attributes = state?.attributes.orEmpty()
  val name = attributes["friendly_name"]?.jsonPrimitive?.contentOrNull ?: entityId
  val unit = attributes["unit_of_measurement"]?.jsonPrimitive?.contentOrNull
  val accent = remember(state) { state?.let { HaStateColor.activeFor(it) } ?: HaStateColor.Neutral }
  var hours by rememberSaveable { mutableIntStateOf(DEFAULT_HOURS) }

  // Null while a range loads; seeded for the default range so a preview needs no fetch.
  val fetched by
    produceState(
      initialValue = initialHistory.takeIf { hours == DEFAULT_HOURS },
      entityId,
      hours,
    ) {
      if (hours == DEFAULT_HOURS && initialHistory != null) {
        value = initialHistory
        return@produceState
      }
      value = null
      value = runCatching { loadHistory(hours) }.getOrDefault(emptyList())
    }
  val history = remember(fetched, state) { fetched?.let { withLiveTail(it, state) } }

  Column(
    modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Header(name, entityId, state, accent)
    Hero(state, unit, nowMs)

    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
      RANGES.forEachIndexed { index, (label, rangeHours) ->
        SegmentedButton(
          selected = hours == rangeHours,
          onClick = { hours = rangeHours },
          shape = SegmentedButtonDefaults.itemShape(index, RANGES.size),
          icon = {},
        ) {
          Text(label)
        }
      }
    }

    Surface(
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
      Box(Modifier.padding(16.dp)) { HistoryPanel(history, unit, hours, accent, nowMs) }
    }

    if (history != null) HistoryStats(history, unit, accent, nowMs)

    Attributes(state, attributes)

    if (onOpenHistory != null) {
      FilledTonalButton(
        onClick = onOpenHistory,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
      ) {
        Icon(
          Icons.AutoMirrored.Filled.OpenInNew,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("Open history in Home Assistant")
      }
    }
  }
}

/** The entity's icon on a tint of its accent, its name and its id. */
@Composable
private fun Header(name: String, entityId: String, state: EntityState?, accent: Color) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    val icon = remember(state) { state?.let { HaIconMap.resolveForState(null, it) } }
    Box(
      Modifier.size(48.dp).background(accent.copy(alpha = 0.18f), CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        icon ?: HaIconMap.Fallback,
        contentDescription = null,
        tint = accent,
        modifier = Modifier.size(26.dp),
      )
    }
    Column(Modifier.weight(1f)) {
      Text(
        name,
        style = MaterialTheme.typography.titleLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        entityId,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** The current value large, its unit beside it, and how long it has held. */
@Composable
private fun Hero(state: EntityState?, unit: String?, nowMs: () -> Long) {
  Column(Modifier.padding(horizontal = 24.dp)) {
    Row(verticalAlignment = Alignment.Bottom) {
      Text(
        state?.state?.let { displayState(it) } ?: "Unavailable",
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Medium,
      )
      if (unit != null && state?.state?.toFloatOrNull() != null) {
        Text(
          " $unit",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 4.dp),
        )
      }
    }
    state?.lastChanged?.let { changed ->
      Text(
        "Since ${clockLabel(changed)} · ${durationLabel(nowMs() - changed.toEpochMilliseconds())}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** The chart for [history]: loading, empty, a line chart, or an on/off timeline. */
@Composable
private fun HistoryPanel(
  history: List<HistoryPoint>?,
  unit: String?,
  hours: Int,
  accent: Color,
  nowMs: () -> Long,
) {
  val series = remember(history) { history?.let(::numericSeries).orEmpty() }
  when {
    history == null ->
      Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    history.isEmpty() ->
      Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
        Text(
          "No history in the last ${rangeLabel(hours)}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    isNumeric(history, series) ->
      NumericHistoryChart(series = series, unit = unit, hours = hours, lineColor = accent)
    else ->
      StateTimeline(
        points = history,
        endMs = nowMs(),
        activeColor = accent,
        hours = hours,
        barHeight = 32.dp,
      )
  }
}

/**
 * Min / average / max for a numeric history; for any other, the time in its two longest states
 * (keyed with the timeline's colours) and how often it changed.
 */
@Composable
private fun HistoryStats(
  history: List<HistoryPoint>,
  unit: String?,
  accent: Color,
  nowMs: () -> Long,
) {
  if (history.isEmpty()) return
  val series = remember(history) { numericSeries(history) }
  val stats: List<Stat> =
    remember(history, unit) {
      if (isNumeric(history, series)) {
        val values = series.map { it.second }
        val suffix = unit?.let { " $it" }.orEmpty()
        listOf(
          Stat("Min", formatValue(values.min()) + suffix),
          Stat("Average", formatValue(values.average().toFloat()) + suffix),
          Stat("Max", formatValue(values.max()) + suffix),
        )
      } else {
        timeInStates(history, nowMs()).take(2).map { (s, ms) ->
          Stat(displayState(s), durationLabel(ms), keyState = s)
        } + Stat("Changes", "${changeCount(history)}")
      }
    }
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    stats.forEach { stat ->
      Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.weight(1f),
      ) {
        Column(
          Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text(
            stat.value,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            stat.keyState?.let { key ->
              Box(Modifier.size(8.dp).background(timelineColor(key, accent), CircleShape))
            }
            Text(
              stat.label,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
            )
          }
        }
      }
    }
  }
}

/** One summary tile; [keyState] adds the timeline colour of that state beside the label. */
private data class Stat(val label: String, val value: String, val keyState: String? = null)

/** The last change and the entity's attributes, as label / value rows in one rounded group. */
@Composable
private fun Attributes(state: EntityState?, attributes: Map<String, JsonElement>) {
  val rows = buildList {
    state?.lastChanged?.let { add("Last changed" to dateTimeLabel(it)) }
    state
      ?.lastUpdated
      ?.takeIf { it != state.lastChanged }
      ?.let { add("Last updated" to dateTimeLabel(it)) }
    attributes
      .filterKeys { it !in HIDDEN_ATTRIBUTES }
      .forEach { (key, value) ->
        val text = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
        add(attributeLabel(key) to displayState(text))
      }
  }
  if (rows.isEmpty()) return
  Column(Modifier.padding(horizontal = 16.dp)) {
    Text(
      "Details",
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
    )
    Surface(
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column {
        rows.forEachIndexed { index, (label, value) ->
          if (index > 0) {
            HorizontalDivider(
              Modifier.padding(horizontal = 16.dp),
              color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
          }
          Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            Text(
              label,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.weight(1f),
            )
            Text(
              value,
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1.4f),
              textAlign = TextAlign.End,
            )
          }
        }
      }
    }
  }
}

/**
 * [points] with [state] appended when it is newer than the last fetched point, so the chart ends on
 * the value the sheet shows and moves with each pushed state.
 */
internal fun withLiveTail(points: List<HistoryPoint>, state: EntityState?): List<HistoryPoint> {
  if (state == null) return points
  val at = state.lastUpdated ?: state.lastChanged ?: return points
  val last = points.lastOrNull()
  if (last != null && last.ts >= at) return points
  return points + HistoryPoint(ts = at, state = state.state)
}

/** The numeric samples of [points], paired with their epoch-millisecond time. */
private fun numericSeries(points: List<HistoryPoint>): List<Pair<Long, Float>> =
  points.mapNotNull { p ->
    p.state.toFloatOrNull()?.let { p.ts.toEpochMilliseconds() to it }
  }

/** A line chart reads [points] when at least two are numbers and most of them are. */
private fun isNumeric(points: List<HistoryPoint>, series: List<Pair<Long, Float>>): Boolean =
  series.size >= 2 && series.size * 2 >= points.count { !it.state.isMissingState() }

/** Milliseconds spent in each state over [points] up to [endMs], longest first. */
internal fun timeInStates(points: List<HistoryPoint>, endMs: Long): List<Pair<String, Long>> {
  val totals = linkedMapOf<String, Long>()
  points.forEachIndexed { i, point ->
    val start = point.ts.toEpochMilliseconds()
    val end = points.getOrNull(i + 1)?.ts?.toEpochMilliseconds() ?: maxOf(endMs, start)
    totals[point.state] = (totals[point.state] ?: 0L) + (end - start)
  }
  return totals.entries.map { it.key to it.value }.sortedByDescending { it.second }
}

/** How many times [points] change state (repeated samples of one state don't count). */
internal fun changeCount(points: List<HistoryPoint>): Int =
  points.zipWithNext().count { (a, b) -> a.state != b.state }

/** `on` → `On`, `heat_cool` → `Heat cool`; numbers and mixed-case text stay as they are. */
private fun displayState(state: String): String =
  if (state.any { it.isUpperCase() } || state.toFloatOrNull() != null) state
  else state.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** `state_class` → `State class`. */
private fun attributeLabel(key: String): String =
  key.replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun formatValue(v: Float): String =
  if (v == v.toLong().toFloat()) v.toLong().toString() else "%.1f".format(v)

/** A span as its two largest units: `45 s`, `12 min`, `3 h 5 min`, `2 d 4 h`. */
internal fun durationLabel(ms: Long): String {
  val minutes = (ms / 60_000L).coerceAtLeast(0L)
  return when {
    ms < 60_000L -> "${(ms / 1000L).coerceAtLeast(0L)} s"
    minutes < 60 -> "$minutes min"
    minutes < 24 * 60 -> "${minutes / 60} h" + (minutes % 60).let { if (it > 0) " $it min" else "" }
    else -> "${minutes / (24 * 60)} d" + ((minutes / 60) % 24).let { if (it > 0) " $it h" else "" }
  }
}

private fun rangeLabel(hours: Int): String =
  if (hours % 24 == 0 && hours > 24) "${hours / 24} days" else "$hours hours"

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME_FORMAT: DateTimeFormatter =
  DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss")

private fun clockLabel(instant: Instant): String =
  CLOCK_FORMAT.format(
    java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(ZoneId.systemDefault())
  )

private fun dateTimeLabel(instant: Instant): String =
  DATE_TIME_FORMAT.format(
    java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(ZoneId.systemDefault())
  )

private const val DEFAULT_HOURS = 24

private val RANGES: List<Pair<String, Int>> =
  listOf("6 h" to 6, "24 h" to 24, "3 d" to 72, "7 d" to 168)

/** Attributes the sheet already shows, or that are no use as text. */
private val HIDDEN_ATTRIBUTES =
  setOf("friendly_name", "unit_of_measurement", "icon", "entity_picture", "access_token")
