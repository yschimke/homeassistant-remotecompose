package ee.schimke.ha.rc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay

/**
 * A counter a host advances so cards showing a relative time (`3 h ago`, see [isRelativeTimeState])
 * refresh as the clock moves, though their entities don't: [CachedCardPreview] re-pushes
 * `<id>.state` on each tick, and a re-encode card folds the label into its data signature. Only
 * cards that show a relative time read it, so a tick recomposes nothing else.
 *
 * The default never ticks — previews and screenshot tests stay one frozen frame, and no host pays
 * for a timer it didn't ask for. A dashboard provides [rememberRelativeTimeTick].
 */
val LocalRelativeTimeTick = staticCompositionLocalOf<State<Long>> { NeverTicks }

private val NeverTicks: State<Long> =
  object : State<Long> {
    override val value: Long = 0L
  }

/** A [LocalRelativeTimeTick] value that advances every [periodMs], well inside a minute. */
@Composable
fun rememberRelativeTimeTick(periodMs: Long = 20_000L): State<Long> {
  val tick = remember { mutableLongStateOf(0L) }
  LaunchedEffect(periodMs) {
    while (true) {
      delay(periodMs)
      tick.longValue++
    }
  }
  return tick
}

/**
 * The relative-time tick when any of [entityIds] shows a relative time under [snapshot], else 0 —
 * so only such cards read (and recompose on) [LocalRelativeTimeTick].
 */
@Composable
fun relativeTimeTickFor(
  entityIds: Collection<String>,
  snapshot: ee.schimke.ha.model.HaSnapshot?,
): Long =
  if (snapshot != null && entityIds.any { isRelativeTimeState(snapshot.states[it]) }) {
    LocalRelativeTimeTick.current.value
  } else 0L
