package ee.schimke.ha.rc.cards

import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.HistoryPoint
import ee.schimke.ha.rc.components.sparklineRange
import ee.schimke.ha.rc.components.sparklineSamples
import kotlin.time.Instant

/**
 * Card design support for cards that draw an entity's history as a sparkline (sensor,
 * history-graph). A sparkline always draws `SPARKLINE_SAMPLES` samples, so the only structure its
 * data decides is whether there is a line at all ([HAS_DATA]); the samples and the y-range are
 * bindings under the slot: `<slot>.numeric.<i>`, `<slot>.series_min`, `<slot>.series_span`.
 */
internal object SeriesDesign {
  const val HAS_DATA = "has_data"

  /** The numeric history of [entityId], in order. */
  fun points(snapshot: HaSnapshot, entityId: String): List<Float> =
    snapshot.history[entityId].orEmpty().mapNotNull { it.state.toFloatOrNull() }

  fun hasData(snapshot: HaSnapshot, entityId: String): Boolean =
    points(snapshot, entityId).size >= 2

  /** The sparkline bindings of [points], under [slot]. */
  fun bindings(slot: String, points: List<Float>): Map<String, Float> {
    val samples = sparklineSamples(points)
    if (samples.isEmpty()) return emptyMap()
    val range = sparklineRange(samples)
    return buildMap {
      samples.forEachIndexed { i, v -> put("$slot.numeric.$i", v) }
      put("$slot.series_min", range.min)
      put("$slot.series_span", range.span)
    }
  }

  /** The sparkline bindings for every slot of a card, in slot order. */
  fun slotBindings(
    entityIds: List<String>,
    snapshot: HaSnapshot,
    pointsOf: (String) -> List<Float> = { points(snapshot, it) },
  ): Map<String, Float> = buildMap {
    entityIds.forEachIndexed { i, id ->
      putAll(bindings(CardDesignSlots.slotId(CardDesignSlots.domainOf(id), i), pointsOf(id)))
    }
  }

  /** A placeholder history for a template slot: a two-point line when [hasData], else none. */
  fun placeholderHistory(hasData: Boolean): List<HistoryPoint> =
    if (!hasData) emptyList()
    else
      listOf(
        HistoryPoint(Instant.fromEpochMilliseconds(0), "0"),
        HistoryPoint(Instant.fromEpochMilliseconds(3_600_000), "1"),
      )
}
