package ee.schimke.terrazzo.wear.ui

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.rc.cardEntityIds
import ee.schimke.terrazzo.wear.data.displayState
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** The most entities a card's details screen covers, each with its own chart. */
internal const val MAX_DETAILS_ENTITIES = 6

/** The most facts listed for one entity. */
internal const val MAX_FACTS = 12

/**
 * The entities a card's details are about, main one first: the one the phone named, then every
 * entity the card shows (an `entities` card's rows, a `glance` card's items), at most
 * [MAX_DETAILS_ENTITIES].
 */
internal fun detailsEntityIds(summary: CardSummary): List<String> {
  val card = decodeWearCard(summary.rawJson)
  val shown = card?.let(::cardEntityIds).orEmpty()
  return (listOfNotNull(summary.primaryEntityId.ifBlank { null }) + shown)
    .distinct()
    .take(MAX_DETAILS_ENTITIES)
}

/**
 * The facts listed for [entityId], label first: its state as the card shows it, when it last
 * changed, then its attributes. From [live] (every attribute, read when the screen opened) when
 * there is one, else from the few the phone syncs ([value]).
 */
internal fun entityFacts(
  entityId: String,
  value: EntityValue?,
  live: EntityState?,
  now: Instant,
): List<Pair<String, String>> = buildList {
  when {
    value != null -> add("State" to value.displayState(entityId))
    live != null -> add("State" to live.state)
    else -> {
      add("State" to "Not synced yet")
      return@buildList
    }
  }
  live?.lastChanged?.let { add("Changed" to ago(now - it)) }
  val deviceClass = value?.deviceClass?.ifBlank { null } ?: live?.attributes?.text("device_class")
  deviceClass?.let { add("Type" to it.replace('_', ' ')) }
  if (live != null) {
    addAll(liveAttributeFacts(live))
  } else {
    value?.attributes?.forEach { (key, text) -> if (text.isNotBlank()) add(label(key) to text) }
  }
}
  .take(MAX_FACTS)

/**
 * [live]'s attributes worth reading on a watch: the well-known ones first, labelled and formatted
 * (brightness as a percentage, a cover's position, a thermostat's target), then any other plain
 * value. Never a secret (a camera's `access_token`), and not the lists, colours and limits that
 * only make sense to a control.
 */
internal fun liveAttributeFacts(live: EntityState): List<Pair<String, String>> {
  val attrs = live.attributes
  val known = KNOWN.mapNotNull { (key, label, format) ->
    attrs.text(key)?.let { text -> format(text)?.let { label to it } }
  }
  val rest =
    attrs.keys
      .filter { it !in KNOWN_KEYS && it !in HIDDEN }
      .sorted()
      .mapNotNull { key ->
        when (val element = attrs[key]) {
          is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
          is JsonArray ->
            element
              .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
              .takeIf { it.isNotEmpty() && it.size == element.size }
              ?.joinToString(", ")
          else -> null
        }?.let { label(key) to it.take(MAX_VALUE_LENGTH) }
      }
  return known + rest
}

private data class Known(val key: String, val label: String, val format: (String) -> String?)

private fun percentOf255(text: String) =
  text.toDoubleOrNull()?.let { "${(it / 255 * 100).roundToInt()} %" }

private fun percent(text: String) = text.toDoubleOrNull()?.let { "${it.roundToInt()} %" }

private fun fraction(text: String) = text.toDoubleOrNull()?.let { "${(it * 100).roundToInt()} %" }

private fun plain(text: String) = text.takeIf { it.isNotBlank() }?.replace('_', ' ')

private fun number(text: String) =
  text.toDoubleOrNull()?.let {
    if (it == it.roundToInt().toDouble()) "${it.roundToInt()}" else "$it"
  }

private val KNOWN =
  listOf(
    Known("brightness", "Brightness", ::percentOf255),
    Known("color_temp_kelvin", "Colour temperature") { number(it)?.let { k -> "$k K" } },
    Known("effect", "Effect", ::plain),
    Known("current_position", "Position", ::percent),
    Known("current_tilt_position", "Tilt", ::percent),
    Known("hvac_action", "Action", ::plain),
    Known("current_temperature", "Current", ::number),
    Known("temperature", "Target", ::number),
    Known("target_temp_low", "Target low", ::number),
    Known("target_temp_high", "Target high", ::number),
    Known("current_humidity", "Humidity", ::percent),
    Known("humidity", "Target humidity", ::percent),
    Known("preset_mode", "Preset", ::plain),
    Known("fan_mode", "Fan", ::plain),
    Known("percentage", "Speed", ::percent),
    Known("media_title", "Playing", ::plain),
    Known("media_artist", "Artist", ::plain),
    Known("source", "Source", ::plain),
    Known("volume_level", "Volume", ::fraction),
    Known("changed_by", "Changed by", ::plain),
    Known("battery_level", "Battery", ::percent),
  )

private val KNOWN_KEYS = KNOWN.map { it.key }.toSet()

/** Attributes never listed: secrets, and what only a control or the card itself reads. */
private val HIDDEN =
  setOf(
    "access_token",
    "entity_picture",
    "entity_picture_local",
    "friendly_name",
    "icon",
    "device_class",
    "unit_of_measurement",
    "state_class",
    "supported_features",
    "supported_color_modes",
    "color_mode",
    "hs_color",
    "rgb_color",
    "rgbw_color",
    "rgbww_color",
    "xy_color",
    "color_temp",
    "min_mireds",
    "max_mireds",
    "min_color_temp_kelvin",
    "max_color_temp_kelvin",
    "effect_list",
    "source_list",
    "sound_mode_list",
    "preset_modes",
    "fan_modes",
    "swing_modes",
    "hvac_modes",
    "options",
    "min_temp",
    "max_temp",
    "target_temp_step",
    "min_humidity",
    "max_humidity",
    "min",
    "max",
    "step",
    "mode",
    "pattern",
    "code_format",
    "code_arm_required",
    "attribution",
    "editable",
    "id",
    "restored",
    "assumed_state",
    "frontend_stream_type",
    "last_reset",
    "entity_id",
  )

private const val MAX_VALUE_LENGTH = 60

private fun kotlinx.serialization.json.JsonObject.text(key: String): String? =
  (this[key] as? JsonPrimitive)
    ?.let { it.contentOrNull ?: it.doubleOrNull?.toString() }
    ?.ifBlank { null }

private fun label(key: String) = key.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** [elapsed] as the watch says it: `now`, `5 min ago`, `3 h ago`, `2 d ago`. */
internal fun ago(elapsed: Duration): String =
  when {
    elapsed.inWholeMinutes < 1 -> "now"
    elapsed.inWholeHours < 1 -> "${elapsed.inWholeMinutes} min ago"
    elapsed.inWholeDays < 1 -> "${elapsed.inWholeHours} h ago"
    else -> "${elapsed.inWholeDays} d ago"
  }
