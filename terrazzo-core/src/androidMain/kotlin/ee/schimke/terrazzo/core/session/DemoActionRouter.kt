package ee.schimke.terrazzo.core.session

import ee.schimke.ha.model.EntityState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Direction of motion requested for a cover entity. */
enum class CoverCommand { Open, Close, Stop }

/**
 * Holds in-memory overrides applied on top of the captured demo entity
 * state so demo-mode taps produce visible behaviour. Toggles flip the
 * underlying state immediately; cover commands seed an animation that
 * [snapshotOverrides] resolves to a `current_position` per call (one
 * percent every [COVER_ANIM_STEP_MS] ms until it lands at the target).
 *
 * The router is process-scoped — same lifetime as `DemoData` itself.
 * Live HA sessions don't use it; they call HA services directly.
 *
 * Mutations bump [epoch] so observers can invalidate caches keyed on
 * "did anything just change". The app uses this to evict the
 * `CardDocumentCache` so cards baking state into bytes (e.g. the
 * garage shutter card's `closedFraction`) re-encode after a click.
 */
class DemoActionRouter {
    private val toggleOverrides: MutableMap<String, String> = mutableMapOf()
    private val coverAnimations: MutableMap<String, CoverAnimation> = mutableMapOf()
    private val _epoch = MutableStateFlow(0L)

    /** Bumped on every mutation. Composables can `collectAsState` to recompose. */
    val epoch: StateFlow<Long> = _epoch

    /** Flip a binary entity's state. No-op if [currentState] isn't a known flag. */
    fun applyToggle(entityId: String, currentState: String) {
        val flipped = when (currentState.lowercase()) {
            "on" -> "off"
            "off" -> "on"
            "open" -> "closed"
            "closed" -> "open"
            else -> return
        }
        toggleOverrides[entityId] = flipped
        bump()
    }

    /**
     * Start (or stop) the open/close animation for [entityId]. The
     * animation is sampled by [snapshotOverrides] so each subsequent
     * frame fed to the dashboard advances the position; on completion
     * the cover settles into its terminal `open` / `closed` state.
     */
    fun requestCover(entityId: String, command: CoverCommand, nowMs: Long, currentPosition: Int) {
        when (command) {
            CoverCommand.Stop -> coverAnimations.remove(entityId)
            CoverCommand.Open, CoverCommand.Close -> {
                val target = if (command == CoverCommand.Open) 100 else 0
                if (currentPosition == target) {
                    // Already there — settle the override so the state
                    // label flips even when the position can't advance.
                    toggleOverrides[entityId] = if (target == 100) "open" else "closed"
                } else {
                    coverAnimations[entityId] = CoverAnimation(
                        startMs = nowMs,
                        fromPercent = currentPosition.coerceIn(0, 100),
                        toPercent = target,
                    )
                }
            }
        }
        bump()
    }

    /**
     * Apply current overrides on top of [base], computed at [nowMs].
     * Cover animations that have reached their target on this sample
     * are inlined as the terminal state and the in-memory animation
     * is dropped.
     */
    fun snapshotOverrides(
        base: Map<String, EntityState>,
        nowMs: Long,
    ): Map<String, EntityState> {
        if (toggleOverrides.isEmpty() && coverAnimations.isEmpty()) return base
        val result = base.toMutableMap()
        toggleOverrides.forEach { (id, override) ->
            result[id] = (result[id] ?: return@forEach).copy(state = override)
        }
        val finished = mutableListOf<String>()
        coverAnimations.forEach { (id, anim) ->
            val current = result[id] ?: return@forEach
            val sample = anim.sampleAt(nowMs)
            result[id] = current.withCoverSample(sample)
            if (sample.isFinal) finished += id
        }
        finished.forEach { id ->
            val terminal = result[id] ?: return@forEach
            coverAnimations.remove(id)
            toggleOverrides[id] = terminal.state
        }
        return result
    }

    /** Drop every override; bumps [epoch] so consumers refresh. */
    fun reset() {
        toggleOverrides.clear()
        coverAnimations.clear()
        bump()
    }

    private fun bump() {
        _epoch.value = _epoch.value + 1
    }

    private data class CoverAnimation(
        val startMs: Long,
        val fromPercent: Int,
        val toPercent: Int,
    ) {
        fun sampleAt(nowMs: Long): CoverSample {
            val elapsed = (nowMs - startMs).coerceAtLeast(0L)
            val steps = (elapsed / COVER_ANIM_STEP_MS).toInt()
            val direction = if (toPercent > fromPercent) 1 else -1
            val raw = fromPercent + direction * steps
            val clamped = raw.coerceIn(
                minOf(fromPercent, toPercent),
                maxOf(fromPercent, toPercent),
            )
            val isFinal = clamped == toPercent
            val state = when {
                isFinal && toPercent == 100 -> "open"
                isFinal && toPercent == 0 -> "closed"
                direction > 0 -> "opening"
                else -> "closing"
            }
            return CoverSample(state = state, position = clamped, isFinal = isFinal)
        }
    }

    private data class CoverSample(val state: String, val position: Int, val isFinal: Boolean)

    private fun EntityState.withCoverSample(sample: CoverSample): EntityState {
        val attrs = attributes.toMutableMap()
        attrs["current_position"] = JsonPrimitive(sample.position)
        return copy(state = sample.state, attributes = JsonObject(attrs))
    }

    private companion object {
        /** Per-percent step duration (≈5 s end-to-end at 50 ms). */
        const val COVER_ANIM_STEP_MS: Long = 50L
    }
}

/**
 * Read the cover position the dashboard last saw, defaulting by-state
 * when the integration doesn't expose `current_position`. Visible to
 * dispatchers so they can seed animations from the latest sample
 * rather than always starting from 0/100.
 */
fun EntityState.demoCoverPositionPercent(): Int =
    attributes["current_position"]?.jsonPrimitive?.content?.toIntOrNull()
        ?: when (state) {
            "open" -> 100
            "closed" -> 0
            else -> 50
        }
