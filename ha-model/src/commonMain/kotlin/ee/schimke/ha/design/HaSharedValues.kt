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
 * the names a document declares, so a design that reads none costs nothing. Every value is always
 * pushed: an entity Home Assistant doesn't have reads `is_available` false, an empty `state`, NaN
 * numbers and false flags.
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

  /** One shared value: its full [name], type and what it means, for a designer. */
  data class Value(val name: String, val kind: Kind, val description: String)

  /** The sun integration's entity: on by default in every Home Assistant. */
  const val SUN: String = "sun.sun"

  private val SUN_TIMES =
    listOf(
      "next_dawn" to "dawn",
      "next_rising" to "sunrise",
      "next_noon" to "solar noon",
      "next_setting" to "sunset",
      "next_dusk" to "dusk",
      "next_midnight" to "solar midnight",
    )

  /** Every shared value, by entity. */
  val values: List<Value> = buildList {
    addAll(entityValues(SUN))
    add(
      Value(
        name(SUN, "above_horizon"),
        Kind.Bool,
        "Whether the sun is above the horizon (its state is above_horizon).",
      )
    )
    add(
      Value(
        name(SUN, "elevation"),
        Kind.Float,
        "The sun's elevation above the horizon, in degrees: negative at night, below -6 after " +
          "civil dusk.",
      )
    )
    add(Value(name(SUN, "azimuth"), Kind.Float, "The sun's compass bearing, in degrees."))
    add(Value(name(SUN, "rising"), Kind.Bool, "Whether the sun is rising: true before solar noon."))
    for ((attribute, label) in SUN_TIMES) {
      add(
        Value(
          name(SUN, "$attribute.$MINUTE_OF_DAY"),
          Kind.Float,
          "The next $label, as the minute of the local day (0-1440), like time.minuteOfDay.",
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
    val sun = snapshot.states[SUN]
    strings[name(SUN, "state")] = sun?.state.orEmpty()
    booleans[name(SUN, "is_available")] = sun?.let { HaLiveBindings.isAvailable(it.state) } ?: false
    booleans[name(SUN, "above_horizon")] = sun?.state == "above_horizon"
    floats[name(SUN, "elevation")] = sun.float("elevation")
    floats[name(SUN, "azimuth")] = sun.float("azimuth")
    booleans[name(SUN, "rising")] = sun.attribute("rising")?.booleanOrNull ?: false
    for ((attribute, _) in SUN_TIMES) {
      floats[name(SUN, "$attribute.$MINUTE_OF_DAY")] = sun.minuteOfDay(attribute, timeZone)
    }
    return CardSnapshotBindings(strings, booleans, emptyMap(), floats)
  }

  private fun entityValues(entityId: String) =
    listOf(
      Value(name(entityId, "state"), Kind.Text, "The entity's raw state."),
      Value(
        name(entityId, "is_available"),
        Kind.Bool,
        "Whether Home Assistant has the entity and it isn't unavailable or unknown: guard the " +
          "other values with it.",
      ),
    )

  private fun name(entityId: String, value: String) = "$PREFIX$entityId.$value"

  private fun EntityState?.attribute(key: String): JsonPrimitive? =
    this?.attributes?.get(key) as? JsonPrimitive

  private fun EntityState?.float(key: String): Float = attribute(key)?.floatOrNull ?: Float.NaN

  private fun EntityState?.minuteOfDay(key: String, timeZone: TimeZone): Float {
    val text = attribute(key)?.takeIf { it.isString }?.contentOrNull ?: return Float.NaN
    val instant = runCatching { Instant.parse(text) }.getOrNull() ?: return Float.NaN
    val time = instant.toLocalDateTime(timeZone).time
    return time.hour * 60 + time.minute + time.second / 60f
  }

  private const val MINUTE_OF_DAY = "minute_of_day"
}
