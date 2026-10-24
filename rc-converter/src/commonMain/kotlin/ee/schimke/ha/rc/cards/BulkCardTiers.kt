package ee.schimke.ha.rc.cards

import ee.schimke.ha.rc.components.RemoteHaSummaryLine

/**
 * Width breakpoint (dp) that the bulk / time-series card converters (history-graph,
 * statistics-graph, logbook, todo-list, calendar, markdown) use to switch between their
 * **identity** tier and their full list/graph in [ee.schimke.ha.rc.CardSizeMode.Fixed].
 *
 * Below the threshold the card shows only its identity (the spark + latest value / "N left" counter
 * / next-event / latest entry / title + first line); at or above it the full card renders and fills
 * the larger cell by drawing more of the data it already has (Principle 7 — "earn the canvas").
 *
 * A launcher cell is ~72 dp wide, so 180 dp lands the gate between a `2×N` cell (identity) and a
 * `3×N`+ cell (full) — the base−1 / base split the matrix previews bracket. Single threshold by
 * design: alpha010 collapses multi-rung (nested) ladders to tier 0 at playback (#224, see
 * `GaugeCardConverter`).
 */
internal const val BULK_IDENTITY_THRESHOLD_DP: Int = 180

/**
 * Below this height (dp) a Wear slot is the Small container, about 44 dp of content: room for a
 * [RemoteHaSummaryLine], not a title over a list.
 */
internal const val WEAR_SMALL_MAX_HEIGHT_DP: Int = 80

/**
 * The wide hero's icon and value on a Wear Large slot. One capture plays on every host, so they are
 * sized for the narrowest: Galaxy's stadium leaves about 160 dp across, room for a 40 dp icon
 * beside "21.5 °C" at value size.
 */
internal val WearHeroScale =
  ee.schimke.ha.rc.components.HaHeroScale(
    iconDp = 40,
    name = ee.schimke.ha.rc.components.HaType.Title,
    state = ee.schimke.ha.rc.components.HaType.Value,
  )
