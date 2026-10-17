package ee.schimke.ha.rc.cards

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaTileFeature
import ee.schimke.ha.rc.components.HaTileFeaturePosition
import ee.schimke.ha.rc.components.HaTileModeOption
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.formatValueWithUnit
import ee.schimke.ha.rc.icons.HaIconMap
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What a `tile` card shows beyond its name and plain state, after Home Assistant's `hui-tile-card`:
 * the secondary line (`state_content`, `state-display.ts`), the `hvac_action` badge
 * (`tile-badge-climate.ts`) and the `features:` rows (`hui-card-features`). Pure functions of the
 * card config and the entity, so the converter can bake them and tests can read them.
 *
 * Everything here is baked into the document, so a tile that uses any of it re-encodes on data
 * changes ([bakes] → `TileCardConverter.dataSignature`); a plain tile keeps the `<id>.state`
 * binding.
 */
internal object TileContent {

  /**
   * HA's `DEFAULT_STATE_CONTENT_DOMAINS`, for the domains whose tiles don't render from card
   * designs: a climate tile reads "Heat · 22.8 °C". (HA's light / fan defaults — brightness,
   * percentage — and timer / update are not applied: light tiles render from card designs, which
   * bind the plain state.)
   */
  private val DEFAULT_STATE_CONTENT: Map<String, List<String>> =
    mapOf(
      "climate" to listOf("state", "current_temperature"),
      "cover" to listOf("state", "current_position"),
      "humidifier" to listOf("state", "current_humidity"),
      "valve" to listOf("state", "current_position"),
    )

  /** HA's `HIDDEN_ZERO_ATTRIBUTES_DOMAINS`: attributes left out while they read 0. */
  private val HIDDEN_ZERO: Map<String, Set<String>> =
    mapOf(
      "valve" to setOf("current_position"),
      "cover" to setOf("current_position"),
      "fan" to setOf("percentage"),
      "light" to setOf("brightness"),
    )

  /** HA's `TEMPERATURE_ATTRIBUTES`: shown in the temperature unit. */
  private val TEMPERATURE_ATTRIBUTES =
    setOf(
      "temperature",
      "current_temperature",
      "target_temperature",
      "target_temp_high",
      "target_temp_low",
      "target_temp_step",
      "min_temp",
      "max_temp",
    )

  /** HA's `DOMAIN_ATTRIBUTES_UNITS` percentages. */
  private val PERCENT_ATTRIBUTES =
    setOf(
      "humidity",
      "current_humidity",
      "target_humidity_low",
      "target_humidity_high",
      "current_position",
      "current_tilt_position",
      "percentage",
      "brightness",
    )

  private const val SEPARATOR = " · "

  /**
   * The tile's `state_content` (a string or a list), normalised as HA's `state-display` does, else
   * the domain default — or null for the plain state, which stays a live binding.
   */
  fun stateContent(card: CardConfig, entityId: String?): List<String>? {
    val configured =
      when (val raw = card.raw["state_content"]) {
        is JsonPrimitive -> raw.contentOrNull?.let { listOf(it) }
        is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> null
      }?.map {
        when (it) {
          "last-updated" -> "last_updated"
          "last-changed" -> "last_changed"
          else -> it
        }
      }
    val content = configured ?: entityId?.let { DEFAULT_STATE_CONTENT[domainOf(it)] }
    return content?.takeUnless { it == listOf("state") || it.isEmpty() }
  }

  /**
   * The secondary line for [content]: each part HA's `state-display` would show, joined with " · ";
   * parts with no value are skipped, and with none at all the plain state is shown.
   */
  fun secondaryText(content: List<String>, entity: EntityState?): String {
    if (entity == null) return formatState(null)
    val parts = content.mapNotNull { part(it, entity) }
    return if (parts.isEmpty()) formatState(entity) else parts.joinToString(SEPARATOR)
  }

  private fun part(content: String, entity: EntityState): String? {
    val domain = domainOf(entity.entityId)
    return when (content) {
      "state" -> formatState(entity)
      "entity-id",
      "entity_id" -> entity.entityId
      "last_changed" -> entity.lastChanged?.let { formatTimestamp(it.toEpochMilliseconds()) }
      "last_updated" -> entity.lastUpdated?.let { formatTimestamp(it.toEpochMilliseconds()) }
      else -> attributeValue(domain, content, entity)
    }
  }

  /** An attribute as HA's `formatEntityAttributeValue` shows it, or null when it has none. */
  fun attributeValue(domain: String, attribute: String, entity: EntityState): String? {
    val raw = entity.attributes[attribute] as? JsonPrimitive ?: return null
    val text = raw.contentOrNull ?: return null
    val number = raw.doubleOrNull
    if (number != null) {
      if (number == 0.0 && attribute in HIDDEN_ZERO[domain].orEmpty()) return null
      return when {
        attribute in TEMPERATURE_ATTRIBUTES -> formatValueWithUnit(text, temperatureUnit(entity))
        // HA shows a light's brightness (0–255) as a percentage.
        attribute == "brightness" && domain == "light" ->
          formatValueWithUnit((number / 255.0 * 100.0).toInt().toString(), "%")
        attribute in PERCENT_ATTRIBUTES -> formatValueWithUnit(text, "%")
        else -> formatValueWithUnit(text, null)
      }
    }
    return humanize(text)
  }

  /**
   * The `hvac_action` badge HA lays over a climate tile's icon: a flame while heating, a clock
   * while idle, in the colour of the mode the action maps to (`CLIMATE_HVAC_ACTION_TO_MODE`), so
   * idle is neutral. Null without an action, or while it reports `off`.
   */
  fun badge(entity: EntityState?): Badge? {
    entity ?: return null
    if (domainOf(entity.entityId) != "climate") return null
    if (!HaLiveBindings.isAvailable(entity.state)) return null
    val action = entity.string("hvac_action") ?: return null
    if (action == "off") return null
    val mode = HVAC_ACTION_TO_MODE[action] ?: return null
    return Badge(
      icon = HVAC_ACTION_ICONS[action] ?: THERMOSTAT,
      color = HaStateColor.climateModeColor(mode),
      description = humanize(action),
    )
  }

  /** A resolved [badge]: its icon, its disc colour (null: neutral) and its spoken description. */
  data class Badge(val icon: ImageVector, val color: Color?, val description: String)

  /** HA `features_position`: inline only when asked and the tile isn't vertical. */
  fun featuresPosition(card: CardConfig): HaTileFeaturePosition {
    val vertical = (card.raw["vertical"] as? JsonPrimitive)?.contentOrNull == "true"
    val inline = (card.raw["features_position"] as? JsonPrimitive)?.contentOrNull == "inline"
    return if (inline && !vertical) HaTileFeaturePosition.Inline else HaTileFeaturePosition.Bottom
  }

  /**
   * The tile's `features:` this renderer supports for [entity], in config order. Feature types that
   * don't apply to the entity, or that a document can't do (a slider, a dropdown that opens), are
   * left out; a dropdown mode select draws as buttons, since a document can't open a menu.
   */
  fun features(card: CardConfig, entity: EntityState?): List<HaTileFeature> {
    entity ?: return emptyList()
    val configs = card.raw["features"] as? JsonArray ?: return emptyList()
    return configs.mapNotNull { config ->
      val obj = config as? JsonObject ?: return@mapNotNull null
      when (obj.string("type")) {
        "target-temperature" -> targetTemperature(entity)
        "climate-hvac-modes" -> hvacModes(entity, obj)
        "climate-preset-modes" -> modeSelect(entity, obj, ClimatePresetModes, defaultIcons = false)
        "climate-fan-modes" -> modeSelect(entity, obj, ClimateFanModes, defaultIcons = false)
        "climate-swing-modes" -> modeSelect(entity, obj, ClimateSwingModes, defaultIcons = false)
        else -> null
      }
    }
  }

  /** Whether the tile bakes anything here, so it must re-encode on data changes. */
  fun bakes(card: CardConfig, entityId: String?, entity: EntityState?): Boolean =
    stateContent(card, entityId) != null ||
      badge(entity) != null ||
      features(card, entity).isNotEmpty()

  // --- target-temperature ---------------------------------------------------------------------

  private const val CLIMATE_TARGET_TEMPERATURE = 1
  private const val CLIMATE_TARGET_TEMPERATURE_RANGE = 2

  /**
   * HA's `target-temperature` feature (`hui-target-temperature-card-feature`): − value + over the
   * single set point, stepping by `target_temp_step` (else 0.5, or 1 in °F) within `min_temp` …
   * `max_temp`; each button calls `<domain>.set_temperature` with the stepped value. A range
   * thermostat (`target_temp_low` / `high` without `temperature`) isn't drawn; one with no set
   * point right now (switched off) draws disabled, as HA does.
   */
  fun targetTemperature(entity: EntityState): HaTileFeature.NumberButtons? {
    val domain = domainOf(entity.entityId)
    if (domain != "climate" && domain != "water_heater") return null
    val target = entity.number("temperature")
    val features = entity.number("supported_features")?.toInt()
    val supported =
      target != null ||
        (features != null &&
          features and (CLIMATE_TARGET_TEMPERATURE or CLIMATE_TARGET_TEMPERATURE_RANGE) != 0)
    if (!supported) return null
    if (target == null && entity.number("target_temp_low") != null) return null
    val unit = temperatureUnit(entity)
    val step = entity.number("target_temp_step") ?: if (unit == "°F") 1.0 else 0.5
    val description = "Target temperature"
    if (target == null || !HaLiveBindings.isAvailable(entity.state)) {
      return HaTileFeature.NumberButtons("—", HaAction.None, HaAction.None, description)
    }
    val min = entity.number("min_temp") ?: 7.0
    val max = entity.number("max_temp") ?: 35.0
    val digits = BigDecimal.valueOf(step).stripTrailingZeros().scale().coerceAtLeast(0)
    fun setTo(value: Double): HaAction =
      HaAction.CallService(
        domain = domain,
        service = "set_temperature",
        entityId = entity.entityId,
        serviceData = JsonObject(mapOf("temperature" to JsonPrimitive(round(value, digits)))),
      )
    val current = BigDecimal.valueOf(target)
    val stepBd = BigDecimal.valueOf(step)
    val down = current.subtract(stepBd).toDouble().coerceAtLeast(min)
    val up = current.add(stepBd).toDouble().coerceAtMost(max)
    return HaTileFeature.NumberButtons(
      valueLabel =
        "${BigDecimal.valueOf(target).setScale(digits, RoundingMode.HALF_UP).toPlainString()} $unit",
      decrement = if (target <= min) HaAction.None else setTo(down),
      increment = if (target >= max) HaAction.None else setTo(up),
      description = description,
    )
  }

  private fun round(value: Double, digits: Int): Double =
    BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP).toDouble()

  // --- mode selects ---------------------------------------------------------------------------

  /**
   * A climate mode attribute a mode-select feature sets (HA `hui-mode-select-card-feature-base`).
   */
  private data class ModeSpec(
    val attribute: String,
    val modesAttribute: String,
    /** The feature config key that narrows and orders the modes. */
    val configKey: String,
    val service: String,
  )

  private val ClimatePresetModes =
    ModeSpec("preset_mode", "preset_modes", "preset_modes", "set_preset_mode")
  private val ClimateFanModes = ModeSpec("fan_mode", "fan_modes", "fan_modes", "set_fan_mode")
  private val ClimateSwingModes =
    ModeSpec("swing_mode", "swing_modes", "swing_modes", "set_swing_mode")

  /** HA's `HVAC_MODES` order; the feature lists the entity's modes in its reverse (off first). */
  private val HVAC_MODES = listOf("auto", "heat_cool", "heat", "cool", "dry", "fan_only", "off")

  /** HA's English hvac mode names (`component.climate.entity_component._.state`). */
  private val HVAC_MODE_LABELS =
    mapOf(
      "off" to "Off",
      "heat" to "Heat",
      "cool" to "Cool",
      "heat_cool" to "Heat/Cool",
      "auto" to "Auto",
      "dry" to "Dry",
      "fan_only" to "Fan only",
    )

  /** HA's `CLIMATE_HVAC_MODE_ICONS` (the frontend's `data/climate.ts`), as bundled MDI glyphs. */
  private val HVAC_MODE_ICONS: Map<String, ImageVector> =
    mdiIcons(
      "cool" to "snowflake",
      "dry" to "water-percent",
      "fan_only" to "fan",
      "auto" to "thermostat-auto",
      "heat" to "fire",
      "off" to "power",
      "heat_cool" to "sun-snowflake-variant",
    )

  /** HA's `CLIMATE_HVAC_ACTION_TO_MODE`. */
  private val HVAC_ACTION_TO_MODE =
    mapOf(
      "cooling" to "cool",
      "defrosting" to "heat",
      "drying" to "dry",
      "fan" to "fan_only",
      "heating" to "heat",
      "idle" to "off",
      "off" to "off",
      "preheating" to "heat",
    )

  /** HA's `CLIMATE_HVAC_ACTION_ICONS` (the frontend's `data/climate.ts`), as bundled MDI glyphs. */
  private val HVAC_ACTION_ICONS: Map<String, ImageVector> =
    mdiIcons(
      "cooling" to "snowflake",
      "defrosting" to "snowflake-melt",
      "drying" to "water-percent",
      "fan" to "fan",
      "heating" to "fire",
      "idle" to "clock-outline",
      "off" to "power",
      "preheating" to "heat-wave",
    )

  /** A climate glyph when an action or mode has none of its own: HA's `mdi:thermostat`. */
  private val THERMOSTAT: ImageVector = requireNotNull(HaIconMap.mdi("thermostat"))

  private fun mdiIcons(vararg names: Pair<String, String>): Map<String, ImageVector> =
    names.associate { (key, name) ->
      key to requireNotNull(HaIconMap.mdi(name)) { "mdi:$name is not bundled" }
    }

  /**
   * HA's `climate-hvac-modes` feature: the entity's `hvac_modes` (narrowed and ordered by the
   * feature's `hvac_modes`), the current one (the entity's state) selected, each calling
   * `climate.set_hvac_mode`. Icons by default, as HA's `icons` style.
   */
  fun hvacModes(entity: EntityState, config: JsonObject): HaTileFeature.ModeSelect? {
    if (domainOf(entity.entityId) != "climate") return null
    val supported = entity.stringList("hvac_modes")
    val ordered = supported.sortedBy { HVAC_MODES.indexOf(it) }.reversed()
    val modes = filterModes(ordered, config.stringListOrNull("hvac_modes"))
    if (modes.isEmpty()) return null
    val available = HaLiveBindings.isAvailable(entity.state)
    val options = modes.map { mode ->
      HaTileModeOption(
        label = HVAC_MODE_LABELS[mode] ?: humanize(mode),
        icon = HVAC_MODE_ICONS[mode] ?: THERMOSTAT,
        selected = entity.state == mode,
        action =
          if (!available) HaAction.None
          else
            HaAction.CallService(
              domain = "climate",
              service = "set_hvac_mode",
              entityId = entity.entityId,
              serviceData = JsonObject(mapOf("hvac_mode" to JsonPrimitive(mode))),
            ),
      )
    }
    return HaTileFeature.ModeSelect(options, showIcons = showIcons(config, defaultIcons = true))
  }

  private fun modeSelect(
    entity: EntityState,
    config: JsonObject,
    spec: ModeSpec,
    defaultIcons: Boolean,
  ): HaTileFeature.ModeSelect? {
    if (domainOf(entity.entityId) != "climate") return null
    val modes =
      filterModes(entity.stringList(spec.modesAttribute), config.stringListOrNull(spec.configKey))
    if (modes.isEmpty()) return null
    val available = HaLiveBindings.isAvailable(entity.state)
    val current = entity.string(spec.attribute)
    val options = modes.map { mode ->
      HaTileModeOption(
        label = humanize(mode),
        icon = null,
        selected = current == mode,
        action =
          if (!available) HaAction.None
          else
            HaAction.CallService(
              domain = "climate",
              service = spec.service,
              entityId = entity.entityId,
              serviceData = JsonObject(mapOf(spec.attribute to JsonPrimitive(mode))),
            ),
      )
    }
    return HaTileFeature.ModeSelect(options, showIcons = showIcons(config, defaultIcons))
  }

  /**
   * HA's `filterModes`: the configured modes the entity supports, in the configured order, else
   * every supported mode.
   */
  private fun filterModes(supported: List<String>, selected: List<String>?): List<String> =
    selected?.filter { it in supported } ?: supported

  /**
   * HA's `style`: `icons` draws icons, `buttons` labels; `dropdown` (a menu a document can't open)
   * draws labelled buttons too.
   */
  private fun showIcons(config: JsonObject, defaultIcons: Boolean): Boolean =
    when (config.string("style")) {
      "icons" -> true
      "buttons",
      "dropdown" -> false
      else -> defaultIcons
    }

  // --- helpers --------------------------------------------------------------------------------

  private fun domainOf(entityId: String): String = entityId.substringBefore('.')

  /** The climate temperature unit: the entity's own, else °C (no unit system in the snapshot). */
  private fun temperatureUnit(entity: EntityState): String =
    entity.string("temperature_unit") ?: entity.string("unit_of_measurement") ?: "°C"

  private val TimestampFormat = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.US)

  /**
   * An absolute timestamp. HA shows `last_changed` relative ("5 minutes ago"), which a baked
   * document re-encoded only on data changes would leave stale.
   */
  private fun formatTimestamp(epochMillis: Long): String =
    TimestampFormat.format(
      java.time.Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    )

  /** `fan_only` → `Fan only`: HA's untranslated fallback, as [formatState] does for states. */
  private fun humanize(raw: String): String =
    raw.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

  private fun JsonObject.stringListOrNull(key: String): List<String>? =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

  private fun EntityState.string(key: String): String? = attributes.string(key)

  private fun EntityState.number(key: String): Double? {
    val p = attributes[key] as? JsonPrimitive ?: return null
    return p.doubleOrNull ?: p.longOrNull?.toDouble()
  }

  private fun EntityState.stringList(key: String): List<String> =
    attributes.stringListOrNull(key).orEmpty()
}
