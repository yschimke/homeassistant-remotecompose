package ee.schimke.ha.design

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardSnapshotBindings
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * A **binding recipe**: how to compute a design template's card-level bindings (`card.min`,
 * `card.range`, a slot value the card reads differently) from the card config and entity states
 * alone, written as data so any host can follow it.
 *
 * Slot bindings ([CardDesignSlots.slotBindings]) are the same for every card; card-level ones
 * differ per card type and used to exist only as Kotlin (`CardConverter.designBindings`). A host
 * without the converters — the JavaScript embed in Home Assistant — had to reject any template that
 * declared one. A template captured from a converter with a recipe carries it as the named string
 * [BINDING], and such a host evaluates it with its own port of [evaluate].
 *
 * The JSON form (version [VERSION]):
 * ```
 * { "v": 1, "prototype": "gauge",
 *   "floats":  { "<binding>": <expr>, … },
 *   "strings": { "<binding>": <expr>, … } }
 * ```
 *
 * Binding names starting `$<i>.` name slot `i` (`$0.numeric_state` → `sensor.s0.numeric_state`).
 * Floats are evaluated first, then strings, each in order; a binding whose expression is null is
 * left out. An expression is one of:
 * - a JSON number or string literal;
 * - `{"config": "a.b"}`: the card config value at that dotted path (a primitive's content);
 * - `{"state": i}`: slot `i`'s raw state; `{"attr": "name", "slot": i}`: one of its attributes;
 * - `{"number": e}`: `e` parsed as a number, null if it isn't one;
 * - `{"first": [e, …]}`: the first non-null;
 * - `{"ref": "<binding>"}`: an already evaluated binding;
 * - `{"host": "<name>"}`: a value the host knows beyond the card and its states, one of [HOST]:
 *   `temperature_unit`, Home Assistant's own temperature unit (its unit system's, `°C` until the
 *   host has read it);
 * - `{"fn": "<name>", "args": [e, …]}`: one of the named functions in [FUNCTIONS].
 *
 * **Compatible additions.** An expression or function a host doesn't know evaluates to null there,
 * so a recipe that uses a newer one follows it with a `first` fallback for an older host: a neutral
 * value (`—`, `°`), never another reading. Changing what an existing expression or function gives
 * is a new [VERSION], which an older host refuses.
 *
 * [prototype] names the card type the recipe was written for, for diagnostics and for a host that
 * prefers a built-in implementation of it. It never changes what [evaluate] does.
 */
class CardBindingRecipe(
  val prototype: String,
  val floats: Map<String, JsonElement>,
  val strings: Map<String, JsonElement> = emptyMap(),
) {
  fun toJson(): JsonObject = buildJsonObject {
    put("v", VERSION)
    put("prototype", prototype)
    put("floats", JsonObject(floats))
    if (strings.isNotEmpty()) put("strings", JsonObject(strings))
  }

  /** The bindings this recipe gives for [card] and [snapshot]. */
  fun evaluate(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    val slots = CardDesignSlots.slotEntities(card).orEmpty()
    val values = HashMap<String, Any>()
    val outFloats = LinkedHashMap<String, Float>()
    val outStrings = LinkedHashMap<String, String>()
    val scope = Scope(card, snapshot, slots, values)
    for ((name, expr) in floats) {
      val value = scope.number(scope.eval(expr)) ?: continue
      val key = scope.bindingName(name) ?: continue
      values[name] = value
      outFloats[key] = value.toFloat()
    }
    for ((name, expr) in strings) {
      val value = scope.eval(expr)?.let(::text) ?: continue
      val key = scope.bindingName(name) ?: continue
      values[name] = value
      outStrings[key] = value
    }
    return CardSnapshotBindings(outStrings, emptyMap(), emptyMap(), outFloats)
  }

  private class Scope(
    val card: CardConfig,
    val snapshot: HaSnapshot,
    val slots: List<String>,
    val values: Map<String, Any>,
  ) {
    fun bindingName(name: String): String? {
      if (!name.startsWith("$")) return name
      val dot = name.indexOf('.')
      val index = name.substring(1, if (dot < 0) name.length else dot).toIntOrNull() ?: return null
      val entityId = slots.getOrNull(index) ?: return null
      val slot = CardDesignSlots.slotId(CardDesignSlots.domainOf(entityId), index)
      return if (dot < 0) slot else slot + name.substring(dot)
    }

    fun eval(expr: JsonElement?): Any? =
      when (expr) {
        null -> null
        is JsonPrimitive ->
          when {
            expr.isString -> expr.content
            else -> expr.contentOrNull?.toDoubleOrNull()
          }
        is JsonArray -> null
        is JsonObject -> evalObject(expr)
      }

    private fun evalObject(expr: JsonObject): Any? {
      expr["config"]?.let { path ->
        return configAt(path.content() ?: return null)
      }
      expr["state"]?.let { slot ->
        return entity(slot)?.state
      }
      expr["attr"]?.let { attr ->
        val name = attr.content() ?: return null
        val value = entity(expr["slot"] ?: JsonPrimitive(0))?.attributes?.get(name)
        return (value as? JsonPrimitive)?.contentOrNull
      }
      expr["number"]?.let {
        return number(eval(it))
      }
      expr["first"]?.let { list ->
        return (list as? JsonArray)?.firstNotNullOfOrNull { eval(it) }
      }
      expr["ref"]?.let {
        return values[it.content()]
      }
      expr["host"]?.let { name ->
        return when (name.content()) {
          HOST_TEMPERATURE_UNIT -> snapshot.temperatureUnit?.takeIf { it.isNotBlank() } ?: "°C"
          else -> null
        }
      }
      expr["fn"]?.let { fn ->
        val args = (expr["args"] as? JsonArray)?.map { eval(it) }.orEmpty()
        return FUNCTIONS[fn.content()]?.invoke(args)
      }
      return null
    }

    private fun entity(slot: JsonElement) =
      (slot as? JsonPrimitive)?.intOrNull?.let { slots.getOrNull(it) }?.let { snapshot.states[it] }

    private fun configAt(path: String): Any? {
      var node: JsonElement = card.raw
      for (part in path.split('.')) node = (node as? JsonObject)?.get(part) ?: return null
      return (node as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonElement.content(): String? = (this as? JsonPrimitive)?.contentOrNull

    fun number(value: Any?): Double? =
      when (value) {
        is Double -> value
        is String -> value.toDoubleOrNull()
        else -> null
      }
  }

  companion object {
    const val VERSION: Int = 1

    /** The named string a template carries its recipe in, as JSON. */
    const val BINDING: String = "meta.binding_recipe"

    /** `{"host": "temperature_unit"}`: Home Assistant's own temperature unit. */
    const val HOST_TEMPERATURE_UNIT: String = "temperature_unit"

    /** The values a `host` expression may name. */
    val HOST: Set<String> = setOf(HOST_TEMPERATURE_UNIT)

    /** The recipe in [json], or null when it isn't one this version understands. */
    fun parse(json: JsonObject): CardBindingRecipe? {
      if ((json["v"] as? JsonPrimitive)?.intOrNull != VERSION) return null
      val prototype = (json["prototype"] as? JsonPrimitive)?.contentOrNull ?: return null
      return CardBindingRecipe(
        prototype,
        (json["floats"] as? JsonObject).orEmpty(),
        (json["strings"] as? JsonObject).orEmpty(),
      )
    }

    // Expression builders, for recipes written in Kotlin.
    fun config(path: String): JsonElement = buildJsonObject { put("config", path) }

    fun state(slot: Int): JsonElement = buildJsonObject { put("state", slot) }

    fun attr(name: String, slot: Int = 0): JsonElement = buildJsonObject {
      put("attr", name)
      put("slot", slot)
    }

    fun number(expr: JsonElement): JsonElement = buildJsonObject { put("number", expr) }

    fun first(vararg exprs: JsonElement): JsonElement = buildJsonObject {
      put("first", buildJsonArray { exprs.forEach { add(it) } })
    }

    fun ref(binding: String): JsonElement = buildJsonObject { put("ref", binding) }

    fun host(name: String): JsonElement = buildJsonObject { put("host", name) }

    fun fn(name: String, vararg args: JsonElement): JsonElement = buildJsonObject {
      put("fn", name)
      put("args", buildJsonArray { args.forEach { add(it) } })
    }

    fun literal(value: Number): JsonElement = JsonPrimitive(value)

    fun literal(value: String): JsonElement = JsonPrimitive(value)

    /**
     * The named functions a recipe may call. A host must implement each exactly as here; a new one
     * is a new recipe [VERSION], since an older host could not follow it.
     */
    val FUNCTIONS: Map<String, (List<Any?>) -> Any?> =
      mapOf(
        // A gauge's range line, null without a unit: "0 – 100 %".
        "range_text" to
          { args ->
            val min = args.getOrNull(0) as? Double
            val max = args.getOrNull(1) as? Double
            val unit = args.getOrNull(2) as? String
            if (min == null || max == null || unit == null) null else rangeText(min, max, unit)
          },
        // A reading with its unit, null without a number: "19.5°C" (formatRangeNumber, no space).
        "with_unit" to
          { args ->
            val value = args.getOrNull(0) as? Double
            val unit = args.getOrNull(1) as? String
            if (value == null || unit == null) null else formatRangeNumber(value) + unit
          },
        // Every argument as text, joined; null when any is null.
        "concat" to
          { args ->
            if (args.any { it == null }) null else args.joinToString("") { text(it!!) }
          },
        // What a thermostat is doing, for display: its hvac_action, else its mode (its state).
        "hvac_label" to
          { args ->
            hvacLabel(args.getOrNull(0) as? String, args.getOrNull(1) as? String)
          },
      )

    /**
     * A thermostat's activity as the thermostat card's chip names it: its `hvac_action` (`heating`
     * reads `Heating`), else its mode (`heat_cool` reads `Heat cool`); null with neither.
     */
    fun hvacLabel(hvacAction: String?, mode: String?): String? =
      when (hvacAction) {
        "heating" -> "Heating"
        "cooling" -> "Cooling"
        "drying" -> "Drying"
        "fan" -> "Fan"
        "idle" -> "Idle"
        "off" -> "Off"
        "preheating" -> "Preheating"
        "defrosting" -> "Defrosting"
        else ->
          mode
            ?.takeIf { it.isNotBlank() }
            ?.replace('_', ' ')
            ?.replaceFirstChar { it.uppercaseChar() }
      }

    /** A gauge's range line: "`min` – `max` `unit`", each bound as [formatRangeNumber]. */
    fun rangeText(min: Double, max: Double, unit: String?): String =
      "${formatRangeNumber(min)} – ${formatRangeNumber(max)} ${unit.orEmpty()}"

    /**
     * A range bound as text: whole numbers without a fraction, others to one decimal, rounding half
     * up, always with a `.` (independent of locale, so every host prints the same).
     */
    fun formatRangeNumber(d: Double): String {
      if (!d.isFinite()) return d.toString()
      if (d == d.toLong().toDouble()) return d.toLong().toString()
      val tenths = floor(d * 10 + 0.5).toLong()
      val sign = if (tenths < 0) "-" else ""
      return "$sign${abs(tenths) / 10}.${abs(tenths) % 10}"
    }

    private fun text(value: Any): String =
      when (value) {
        is Double -> formatRangeNumber(value)
        else -> value.toString()
      }
  }
}

/** Built-in recipes, shared by the converters that declare them and the tests that pin hosts. */
object CardBindingRecipes {
  /** An unset severity band: a threshold no value reaches, as `RemoteHaGauge` bakes it. */
  const val UNSET_THRESHOLD: Double = 1e30

  /** `gauge`: range, severity bands and range line from config; a non-numeric state sits at min. */
  val GAUGE: CardBindingRecipe =
    with(CardBindingRecipe) {
      fun orDefault(path: String, default: Number) = first(number(config(path)), literal(default))
      CardBindingRecipe(
        prototype = "gauge",
        floats =
          linkedMapOf(
            "card.min" to orDefault("min", 0),
            "card.max" to orDefault("max", 100),
            "card.severity_green" to orDefault("severity.green", UNSET_THRESHOLD),
            "card.severity_yellow" to orDefault("severity.yellow", UNSET_THRESHOLD),
            "card.severity_red" to orDefault("severity.red", UNSET_THRESHOLD),
            "\$0.numeric_state" to first(number(state(0)), ref("card.min")),
          ),
        strings =
          linkedMapOf(
            "card.range" to
              fn(
                "range_text",
                ref("card.min"),
                ref("card.max"),
                first(config("unit"), attr("unit_of_measurement")),
              ),
          ),
      )
    }

  /**
   * `tile`: a sensor tile draws its reading's meter glyph off `<slot>.numeric_state`, which the
   * slot bindings leave NaN for a non-numeric state (unavailable, unknown). The glyph rests at 0
   * then; the tile is dimmed through `is_available` anyway.
   */
  val TILE: CardBindingRecipe =
    with(CardBindingRecipe) {
      CardBindingRecipe(
        prototype = "tile",
        floats = linkedMapOf("\$0.numeric_state" to first(number(state(0)), literal(0))),
      )
    }

  /**
   * `cover`: the shutter cards' [count] covers. Slot bindings carry each one's `state_int` and
   * formatted state; the door's height, the position in its state line and which buttons it shows
   * come from `current_position` and `supported_features`, as `HaLiveBindings.attributeFloats`
   * pushes them on the direct path ([HaLiveBindings.ABSENT] when a cover reports none).
   */
  fun cover(count: Int): CardBindingRecipe =
    with(CardBindingRecipe) {
      CardBindingRecipe(
        prototype = "cover",
        floats =
          buildMap {
            for (i in 0 until count) {
              for (attribute in listOf("current_position", "supported_features")) {
                put(
                  "\$$i.$attribute",
                  first(number(attr(attribute, i)), literal(HaLiveBindings.ABSENT)),
                )
              }
            }
          },
      )
    }

  /** [cover] for one cover: the built-in form a host may implement itself. */
  val COVER: CardBindingRecipe = cover(1)

  /**
   * `climate`: what a thermostat design reads of its entity, all in the entity's own unit, as
   * `HaLiveBindings.attributeFloats` pushes them on the direct path:
   * - `<slot>.current_temperature` and `<slot>.target_temperature` (Home Assistant's `temperature`
   *   attribute), [HaLiveBindings.ABSENT] when the thermostat reports none (off, or holding a
   *   range);
   * - `<slot>.min_temp`, `<slot>.max_temp` and `<slot>.target_temp_step`, the set point's range and
   *   step, Home Assistant's defaults (7, 35, 0.5) when it reports none;
   * - `<slot>.unit`, the unit those are in: the entity's own, else Home Assistant's (`°F` on an
   *   imperial system). A host older than the `host` expression shows a bare `°` rather than a
   *   wrong unit;
   * - `<slot>.attributes.current_temperature_label` (`19.5°C`) and `temperature_label` (`↑ 21°C`),
   *   `—` without one, and `hvac_action_label` (`Heating`, else the mode): for a design that shows
   *   them as text. An older host shows `—` and nothing.
   *
   * What a device pack's thermostat designs read.
   */
  val CLIMATE: CardBindingRecipe =
    with(CardBindingRecipe) {
      fun attribute(name: String, absent: Number) = first(number(attr(name, 0)), literal(absent))
      CardBindingRecipe(
        prototype = "climate",
        floats =
          linkedMapOf(
            "\$0.current_temperature" to attribute("current_temperature", HaLiveBindings.ABSENT),
            "\$0.target_temperature" to attribute("temperature", HaLiveBindings.ABSENT),
            "\$0.min_temp" to attribute("min_temp", CLIMATE_MIN_TEMP),
            "\$0.max_temp" to attribute("max_temp", CLIMATE_MAX_TEMP),
            "\$0.target_temp_step" to attribute("target_temp_step", CLIMATE_TEMP_STEP),
          ),
        strings =
          linkedMapOf(
            "\$0.unit" to
              first(
                attr("temperature_unit", 0),
                attr("unit_of_measurement", 0),
                host(HOST_TEMPERATURE_UNIT),
                literal("°"),
              ),
            "\$0.attributes.current_temperature_label" to
              first(
                fn("with_unit", number(attr("current_temperature", 0)), ref("\$0.unit")),
                literal(NO_READING),
              ),
            "\$0.attributes.temperature_label" to
              first(
                fn(
                  "concat",
                  literal("↑ "),
                  fn("with_unit", number(attr("temperature", 0)), ref("\$0.unit")),
                ),
                literal(NO_READING),
              ),
            "\$0.attributes.hvac_action_label" to
              first(fn("hvac_label", attr("hvac_action", 0), state(0)), literal("")),
          ),
      )
    }

  /** Home Assistant's climate defaults, for a thermostat that reports no range or step. */
  const val CLIMATE_MIN_TEMP: Double = 7.0
  const val CLIMATE_MAX_TEMP: Double = 35.0
  const val CLIMATE_TEMP_STEP: Double = 0.5

  /** A label for a reading the entity doesn't report. */
  const val NO_READING: String = "—"

  private val byPrototype = listOf(GAUGE, TILE, COVER, CLIMATE).associateBy { it.prototype }

  fun forPrototype(prototype: String): CardBindingRecipe? = byPrototype[prototype]
}
