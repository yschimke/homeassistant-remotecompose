package ee.schimke.terrazzo.core.session

import ee.schimke.ha.model.HaSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [this] — the snapshot a dashboard was loaded with — moved onto the entity states of [live]. Keeps
 * what a `state_changed` feed doesn't carry (history, statistics, forecasts, themes, locale), and
 * the device registry when [live] has none.
 */
fun HaSnapshot.withLiveStates(live: HaSnapshot): HaSnapshot =
  copy(states = live.states, devices = live.devices.ifEmpty { devices })

/**
 * The snapshot a dashboard loaded with [loaded] should show as [HaSession.liveStates] changes:
 * [loaded] merged with each live snapshot ([withLiveStates]), emitted only when it differs from
 * what was last shown. Cards then pick the change up through their named bindings or
 * `dataSignature` re-encode (docs/architecture/live-card-updates.md). Never completes; a session
 * without a live feed never emits.
 */
fun HaSession.liveDashboardSnapshots(loaded: HaSnapshot): Flow<HaSnapshot> = flow {
  var shown = loaded
  liveStates.collect { live ->
    if (live == null) return@collect
    val next = loaded.withLiveStates(live)
    if (next != shown) {
      shown = next
      emit(next)
    }
  }
}
