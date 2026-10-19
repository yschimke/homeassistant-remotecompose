package ee.schimke.terrazzo.core.session

/**
 * Latest-started-wins guard for concurrent loads writing one piece of state — the dashboard
 * screen's poll loop, its live `state_changed` feed and a demo tap's immediate refetch. Each load
 * calls [begin] before it starts and writes its result only while [isLatest] holds, so a slow older
 * load that finishes after a newer one is dropped instead of overwriting fresher values.
 *
 * Not thread-safe: confine it to one thread, as a composition's effects are.
 */
class LatestWins {
  private var latest = 0L

  /** Starts a load, superseding every load begun before it; returns its generation. */
  fun begin(): Long = ++latest

  /** Whether no load has begun since the one that got [generation]. */
  fun isLatest(generation: Long): Boolean = generation == latest

  /**
   * Runs [load] and hands its result to [apply] only if no other load began meanwhile. Returns
   * whether it was applied; exceptions from [load] propagate.
   */
  suspend fun <T> load(load: suspend () -> T, apply: (T) -> Unit): Boolean {
    val generation = begin()
    val result = load()
    if (!isLatest(generation)) return false
    apply(result)
    return true
  }
}
