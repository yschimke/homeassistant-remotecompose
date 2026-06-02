package ee.schimke.ha.rc

import androidx.compose.runtime.staticCompositionLocalOf
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Source of wall-clock "now" for HA card converters.
 *
 * Anything that needs to read time during the encode pass — formatting a label, picking the next
 * forecast bucket, computing a relative timestamp — MUST go through [LocalHaClock]. Reaching for
 * [Instant.now] / [ZonedDateTime.now] / [System.currentTimeMillis] directly bypasses the preview
 * override and reintroduces wall-clock drift into the captured `.rc` bytes (so the preview PNG
 * ticks each minute and screenshot diffs become useless).
 *
 * Production binds [SystemHaClock] (the implicit default — callers don't need to provide anything).
 * Previews and unit tests provide a [FixedHaClock] so the encoded bytes are byte-stable across
 * runs.
 *
 * Converters that normally bind to the remote-compose player's live tick (the clock card today,
 * future relative-time labels) check [isFrozen]: when true they encode a static label derived from
 * [now]; when false they emit the live binding so the player ticks without a re-encode.
 */
interface HaClock {
  /** Instant of "now". */
  fun now(): Instant

  /**
   * Display zone — converters that render zoned timestamps (clock card, future calendar / logbook
   * labels) use this for formatting unless the card config pins its own `time_zone:`.
   */
  fun zone(): ZoneId

  /**
   * Whether the clock is frozen for the lifetime of this composition. Converters that would
   * otherwise let the remote-compose player tick wall-clock time check this and switch to a
   * static-label path so the preview PNG stays deterministic.
   */
  val isFrozen: Boolean
    get() = false
}

/** Convenience: zoned "now" in the clock's display [HaClock.zone]. */
fun HaClock.zonedNow(): ZonedDateTime = ZonedDateTime.ofInstant(now(), zone())

/**
 * Real wall-clock backed by [Instant.now] + [ZoneId.systemDefault]. The implicit [LocalHaClock]
 * default — production callers don't need to provide anything.
 */
object SystemHaClock : HaClock {
  override fun now(): Instant = Instant.now()

  override fun zone(): ZoneId = ZoneId.systemDefault()
}

/**
 * Clock pinned to a single instant — used by previews and tests so the encoded bytes (and therefore
 * the rendered PNG) are byte-stable across runs. Reports [isFrozen] = true so live-tick converters
 * know to fall back to a static-label encoding.
 */
class FixedHaClock(private val instant: Instant, private val zone: ZoneId = ZoneOffset.UTC) :
  HaClock {
  constructor(zoned: ZonedDateTime) : this(zoned.toInstant(), zoned.zone)

  override fun now(): Instant = instant

  override fun zone(): ZoneId = zone

  override val isFrozen: Boolean = true
}

/**
 * Composition local that supplies the current [HaClock] to card converters. Defaults to
 * [SystemHaClock] so production renders read real wall-clock time without any extra setup. Previews
 * and tests override with a [FixedHaClock].
 */
val LocalHaClock = staticCompositionLocalOf<HaClock> { SystemHaClock }
