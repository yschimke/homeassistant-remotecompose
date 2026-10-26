package ee.schimke.ha.design

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardSnapshotBindings
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * Values any card design may read besides its own slots: entities every Home Assistant has under
 * the same id, in a named-value domain of their own, `HA:<entity_id>.<value>`
 * (`HA:sun.sun.elevation`), beside the card's own in `USER:` (`USER:cover.s0.is_on`).
 *
 * A design names its card's entities by slot (`cover.s0.is_on`), so it serves every card of its
 * shape and never carries anyone's entity ids. An entity with a fixed id needs no slot: `sun.sun`
 * is the sun everywhere. Only the entities listed in [values] are shared, so a design can't read an
 * arbitrary entity of the home it plays in.
 *
 * Every host that plays a design pushes [bindings] beside the card's own, under their full names: a
 * player takes a pushed name with a domain as it is, and puts a bare one in `USER:`. It keeps only
 * the names a document declares, so a design that reads none costs nothing.
 *
 * Every value is always pushed. Without the data (the sun integration off, `sun.sun` unavailable,
 * or one attribute missing) a value reads its [Value.daytime]: a design shows its day look rather
 * than going dark or computing with NaN, and needs no guard. Only `is_available` says the data is
 * missing.
 *
 * Times are pushed as the minute of the local day (0–1440), the scale of the clock's
 * `time.minuteOfDay`, so a design compares them with the clock without parsing a timestamp.
 */
object HaSharedValues {
  /** The named-value domain of shared values, as `USER` is the card's own. */
  const val DOMAIN: String = "HA"

  const val PREFIX: String = "$DOMAIN:"

  enum class Kind {
    Text,
    Bool,
    Float,
  }

  /**
   * One shared value: its full [name], type and what it means, for a designer, and what it reads
   * without the data: [daytime], a `String`, `Boolean` or `Float` as [kind] says.
   */
  data class Value(val name: String, val kind: Kind, val description: String, val daytime: Any)

  /** The sun integration's entity: on by default in every Home Assistant. */
  const val SUN: String = "sun.sun"

  /**
   * The sun's times, with their names and their daytime: a day that never ends, so a design that
   * asks whether the clock is between sunrise and sunset gets day too.
   */
  private val SUN_TIMES =
    listOf(
      Triple("next_dawn", "dawn", 0f),
      Triple("next_rising", "sunrise", 0f),
      Triple("next_noon", "solar noon", 720f),
      Triple("next_setting", "sunset", 1440f),
      Triple("next_dusk", "dusk", 1440f),
      Triple("next_midnight", "solar midnight", 0f),
    )

  /** Every shared value, by entity. */
  val values: List<Value> = buildList {
    add(Value(name(SUN, "state"), Kind.Text, "The sun's state.", ABOVE_HORIZON))
    add(
      Value(
        name(SUN, "is_available"),
        Kind.Bool,
        "Whether Home Assistant has the sun and it isn't unavailable or unknown. Without it, " +
          "every other value reads daytime.",
        false,
      )
    )
    add(
      Value(
        name(SUN, "above_horizon"),
        Kind.Bool,
        "Whether the sun is above the horizon (its state is above_horizon).",
        true,
      )
    )
    add(
      Value(
        name(SUN, "elevation"),
        Kind.Float,
        "The sun's elevation above the horizon, in degrees: negative at night, below -6 after " +
          "civil dusk.",
        45f,
      )
    )
    add(Value(name(SUN, "azimuth"), Kind.Float, "The sun's compass bearing, in degrees.", 180f))
    add(
      Value(
        name(SUN, "rising"),
        Kind.Bool,
        "Whether the sun is rising: true before solar noon.",
        false,
      )
    )
    for ((attribute, label, daytime) in SUN_TIMES) {
      add(
        Value(
          name(SUN, "$attribute.$MINUTE_OF_DAY"),
          Kind.Float,
          "The next $label, as the minute of the local day (0-1440), like time.minuteOfDay.",
          daytime,
        )
      )
    }
  }

  /** The entities [values] read: what a host that fetches only chosen entities also fetches. */
  val entities: Set<String> = setOf(SUN)

  private val byName = values.associateBy { it.name }

  /** The shared value called [name] (`HA:sun.sun.elevation`), or null. */
  fun valueOf(name: String): Value? = byName[name]

  /** Whether [name] is in the shared domain, known or not. */
  fun isShared(name: String): Boolean = name.startsWith(PREFIX)

  /**
   * Every shared value for [snapshot], times in [timeZone]: the device's, the zone the player's
   * clock reads `time.minuteOfDay` in.
   */
  fun bindings(
    snapshot: HaSnapshot,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
  ): CardSnapshotBindings {
    val strings = LinkedHashMap<String, String>()
    val booleans = LinkedHashMap<String, Boolean>()
    val floats = LinkedHashMap<String, Float>()
    // Values read daytime ([Value.daytime]) wherever the data is missing.
    for (value in values) {
      when (val daytime = value.daytime) {
        is String -> strings[value.name] = daytime
        is Boolean -> booleans[value.name] = daytime
        is Float -> floats[value.name] = daytime
      }
    }
    val sun =
      snapshot.states[SUN]?.takeIf { HaLiveBindings.isAvailable(it.state) }
        ?: return bindings(strings, booleans, floats)
    strings[name(SUN, "state")] = sun.state
    booleans[name(SUN, "is_available")] = true
    booleans[name(SUN, "above_horizon")] = sun.state == ABOVE_HORIZON
    sun.float("elevation")?.let { floats[name(SUN, "elevation")] = it }
    sun.float("azimuth")?.let { floats[name(SUN, "azimuth")] = it }
    sun.attribute("rising")?.booleanOrNull?.let { booleans[name(SUN, "rising")] = it }
    for ((attribute, _, _) in SUN_TIMES) {
      sun.minuteOfDay(attribute, timeZone)?.let {
        floats[name(SUN, "$attribute.$MINUTE_OF_DAY")] = it
      }
    }
    return bindings(strings, booleans, floats)
  }

  private fun bindings(
    strings: Map<String, String>,
    booleans: Map<String, Boolean>,
    floats: Map<String, Float>,
  ) = CardSnapshotBindings(strings, booleans, emptyMap(), floats)

  private fun name(entityId: String, value: String) = "$PREFIX$entityId.$value"

  private fun EntityState.attribute(key: String): JsonPrimitive? = attributes[key] as? JsonPrimitive

  private fun EntityState.float(key: String): Float? = attribute(key)?.floatOrNull

  private fun EntityState.minuteOfDay(key: String, timeZone: TimeZone): Float? {
    val text = attribute(key)?.takeIf { it.isString }?.contentOrNull ?: return null
    val instant = runCatching { Instant.parse(text) }.getOrNull() ?: return null
    val time = instant.toLocalDateTime(timeZone).time
    return time.hour * 60 + time.minute + time.second / 60f
  }

  private const val MINUTE_OF_DAY = "minute_of_day"
  private const val ABOVE_HORIZON = "above_horizon"
}
