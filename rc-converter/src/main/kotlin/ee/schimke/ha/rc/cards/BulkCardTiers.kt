package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.LocalFixedCanvasAxis
import ee.schimke.ha.rc.RemoteSizeBreakpoint
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
 * A bulk card's Fixed-mode tiers, behind one gate (#224): on a Wear slot the gate is height, so the
 * Small container gets [summary] and the Large one [full] (Wear slots are all wide enough for the
 * full card); everywhere else it is width, [identity] below [BULK_IDENTITY_THRESHOLD_DP] and [full]
 * at or above it.
 */
@Composable
internal fun BulkTiers(
  modifier: RemoteModifier,
  summary: @Composable () -> Unit,
  identity: @Composable () -> Unit,
  full: @Composable () -> Unit,
) {
  if (LocalFixedCanvasAxis.current == BreakpointAxis.Height) {
    RemoteSizeBreakpoint(
      thresholdsDp = intArrayOf(WEAR_SMALL_MAX_HEIGHT_DP),
      modifier = modifier,
      axis = BreakpointAxis.Height,
    ) { tier ->
      if (tier == 0) summary() else full()
    }
  } else {
    RemoteSizeBreakpoint(
      thresholdsDp = intArrayOf(BULK_IDENTITY_THRESHOLD_DP),
      modifier = modifier,
      axis = BreakpointAxis.Width,
    ) { tier ->
      if (tier == 0) identity() else full()
    }
  }
}
