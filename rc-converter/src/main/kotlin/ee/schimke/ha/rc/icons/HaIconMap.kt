package ee.schimke.ha.rc.icons

import androidx.compose.ui.graphics.vector.ImageVector
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.rc.icons.mdi.MdiIcons
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * An entity's icon as Home Assistant draws it, in Material Design Icons ([MdiIcons]).
 *
 * The order follows the frontend's `entityIcon`:
 * 1. an `icon:` the card sets (`mdi:` or the legacy `hass:` names),
 * 2. the entity's own `icon` attribute,
 * 3. the frontend's state-icon special cases (`sun`, `update`, `device_tracker`, `input_datetime`),
 * 4. core's icon table for the entity's domain and device class, with its state variants
 *    ([HaIconTables.RULES]),
 * 5. the frontend's domain fallback, then its default ([HaIconTables.DEFAULT_ICON], a bookmark).
 *
 * An unknown name in steps 1–2 (a custom icon set, or an MDI icon this build doesn't bundle) falls
 * through to the next step rather than drawing nothing.
 *
 * **Live updates.** Most cards are bound documents: captured once, then fed values by name, and
 * often rendered from a card design captured in another state entirely
 * (`docs/architecture/live-card-updates.md`). So [resolve] draws what such a document can keep
 * true:
 * * an on/off entity draws whichever of its two glyphs isn't a negative one (`lightbulb`, not
 *   `lightbulb-off`), and [entityIcons] gives both to components that swap them in-document on the
 *   entity's `is_on` binding;
 * * a numeric range (a battery's level) draws the rule's level-independent glyph;
 * * any other state-following glyph (sun, alarm panel, weather, lock, cover, …) draws the current
 *   state's, and [stateSignature] changes with it so the dashboard re-encodes the card.
 *
 * [resolveForState] is Home Assistant's exact icon for one state, for content re-encoded with it.
 */
object HaIconMap {
  /** Drawn when nothing more specific applies — Home Assistant's `DEFAULT_DOMAIN_ICON`. */
  val Fallback: ImageVector
    get() = glyph(HaIconTables.DEFAULT_ICON)

  /** The icon a bound document draws for [entity], honouring the card's [iconOverride]. */
  fun resolve(iconOverride: String?, entity: EntityState?): ImageVector =
    glyph(resolveName(iconOverride, entity))

  /** [resolve], as the MDI name it draws (no `mdi:` prefix). */
  fun resolveName(iconOverride: String?, entity: EntityState?): String =
    fixedName(iconOverride, entity)
      ?: entity?.let { if (isToggle(it)) neutralName(it) else defaultName(it, it.state, false) }
      ?: HaIconTables.DEFAULT_ICON

  /**
   * Home Assistant's icon for [entity] in [state], on/off and range variants included: for content
   * re-encoded with the state it shows.
   */
  fun resolveForState(
    iconOverride: String?,
    entity: EntityState,
    state: String = entity.state,
  ): ImageVector = glyph(resolveNameForState(iconOverride, entity, state))

  /** [resolveForState], as the MDI name it draws. */
  fun resolveNameForState(
    iconOverride: String?,
    entity: EntityState,
    state: String = entity.state,
  ): String = fixedName(iconOverride, entity) ?: defaultName(entity, state, ranges = true)

  /**
   * The glyphs for a component bound to the entity's `is_on`: [HaEntityIcons.icon] while off and
   * [HaEntityIcons.activeIcon] while on, when an on/off entity's icon changes with its state — a
   * light's `lightbulb-off` / `lightbulb`, a door sensor's `door-closed` / `door-open` — so a
   * document captured in one state draws the other
   * ([ee.schimke.ha.rc.components.HaToggleAccent.activeIcon]). Otherwise [resolve] alone.
   */
  fun entityIcons(iconOverride: String?, entity: EntityState?): HaEntityIcons {
    if (entity != null && isToggle(entity) && fixedName(iconOverride, entity) == null) {
      val (off, on) = toggleNames(entity)
      if (off != on) return HaEntityIcons(glyph(off), glyph(on))
    }
    return HaEntityIcons(resolve(iconOverride, entity), activeIcon = null)
  }

  /**
   * The glyph [resolve] draws for [entity], when that glyph follows the entity's state other than
   * through `is_on` ([entityIcons] covers on/off domains); null when it doesn't, or the entity
   * fixes its own icon. A host folds it into a converter-rendered card's cache key so the card
   * re-encodes when the glyph changes, while values keep arriving as bindings.
   */
  fun stateSignature(entity: EntityState?): String? {
    if (entity == null || fixedName(null, entity) != null) return null
    val domain = domainOf(entity)
    if (isToggle(entity)) return null // swapped in-document: see [entityIcons]
    val followsState =
      domain in STATE_ICON_DOMAINS ||
        rule(domain, entity.deviceClass())?.states?.isNotEmpty() == true
    return if (followsState) defaultName(entity, entity.state, ranges = false) else null
  }

  /** The glyph for a weather `condition` (`sunny`, `partlycloudy`, `pouring`, …). */
  fun weatherCondition(condition: String?): ImageVector {
    val rule = rule("weather", null)!!
    return glyph(condition?.let { rule.states[it] } ?: rule.default)
  }

  /**
   * The bundled glyph for an icon name (`mdi:water-percent`, `hass:fan`, `weather-sunny`), or null
   * when it isn't an MDI name this build bundles.
   */
  fun mdi(raw: String?): ImageVector? = mdiName(raw)?.let { MdiIcons[it] }

  /** The bundled MDI name [raw] names (prefix stripped, lower-cased), or null. */
  fun mdiName(raw: String?): String? {
    val trimmed = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val name =
      when {
        trimmed.startsWith("mdi:") -> trimmed.removePrefix("mdi:")
        trimmed.startsWith("hass:") -> trimmed.removePrefix("hass:")
        ':' in trimmed -> return null // another icon set (`phu:`, `si:`, …)
        else -> trimmed
      }
    return name.takeIf { it in MdiIcons }
  }

  // --- resolution ---

  /** Steps 1–2: an icon the card or the entity fixes, when it is a bundled MDI name. */
  private fun fixedName(iconOverride: String?, entity: EntityState?): String? =
    mdiName(iconOverride)
      ?: mdiName((entity?.attributes?.get("icon") as? JsonPrimitive)?.contentOrNull)

  private fun toggleNames(entity: EntityState): Pair<String, String> =
    defaultName(entity, "off", ranges = false) to defaultName(entity, "on", ranges = false)

  /**
   * An on/off entity's glyph for a document that can't swap: the one of its pair that doesn't
   * negate (`-off`, `close-`), so a light reads as a light in either state.
   */
  private fun neutralName(entity: EntityState): String {
    val (off, on) = toggleNames(entity)
    fun negative(name: String) = name.endsWith("-off") || name.startsWith("close-")
    return if (negative(off) && !negative(on)) on else off
  }

  /** Steps 3–5 for [entity] in [state]. */
  private fun defaultName(entity: EntityState, state: String, ranges: Boolean): String {
    val domain = domainOf(entity)
    stateIconName(domain, entity, state)?.let {
      return it
    }
    rule(domain, entity.deviceClass())?.let {
      return it.iconFor(state, ranges)
    }
    return DomainFallbacks[domain] ?: HaIconTables.DEFAULT_ICON
  }

  /** The frontend's `stateIcon()` special cases. */
  private fun stateIconName(domain: String, entity: EntityState, state: String): String? =
    when (domain) {
      "update" ->
        when {
          entity.installing() -> "package-down"
          state == "on" -> "package-up"
          else -> "package"
        }
      "device_tracker" ->
        when (entity.attr("source_type")?.contentOrNull) {
          "router" -> if (state == "home") "lan-connect" else "lan-disconnect"
          "bluetooth",
          "bluetooth_le" -> if (state == "home") "bluetooth-connect" else "bluetooth"
          else -> if (state == "not_home") "account-arrow-right" else "account"
        }
      "sun" -> if (state == "above_horizon") "white-balance-sunny" else "weather-night"
      "input_datetime" ->
        when {
          entity.attr("has_date")?.booleanOrNull == false -> "clock"
          entity.attr("has_time")?.booleanOrNull == false -> "calendar"
          else -> null
        }
      else -> null
    }

  private fun rule(domain: String, deviceClass: String?): HaIconRule? {
    val rules = Rules[domain] ?: return null
    return deviceClass?.let { rules[it] } ?: rules["_"]
  }

  private fun HaIconRule.iconFor(state: String, ranges: Boolean): String {
    states[state]?.let {
      return it
    }
    if (range.isEmpty()) return default
    if (!ranges) return stableIcon
    val value = state.toDoubleOrNull() ?: return default
    // The highest threshold at or below the value; below the first one, the default.
    return range.lastOrNull { (threshold, _) -> value >= threshold }?.second ?: default
  }

  private fun glyph(name: String): ImageVector =
    MdiIcons[name] ?: MdiIcons[HaIconTables.DEFAULT_ICON]!!

  private fun domainOf(entity: EntityState): String = entity.entityId.substringBefore('.')

  private fun isToggle(entity: EntityState): Boolean = domainOf(entity) in TOGGLE_DOMAINS

  private fun EntityState.attr(key: String): JsonPrimitive? = attributes[key] as? JsonPrimitive

  /** `in_progress` is a boolean, or an install percentage on older integrations. */
  private fun EntityState.installing(): Boolean {
    val progress = attr("in_progress") ?: return false
    return progress.booleanOrNull ?: ((progress.doubleOrNull ?: 0.0) > 0.0)
  }

  private fun EntityState.deviceClass(): String? = attr("device_class")?.contentOrNull

  /**
   * Domains whose `is_on` binding is exactly "state is `on`" (`HaLiveBindings.isOn`, through the
   * typed entity's `isActive`), so an on/off glyph pair can follow it in-document.
   */
  private val TOGGLE_DOMAINS =
    setOf("binary_sensor", "fan", "humidifier", "input_boolean", "light", "siren", "switch")

  /** Domains whose glyph follows the state through [stateIconName]. */
  private val STATE_ICON_DOMAINS = setOf("update", "device_tracker", "sun")

  /**
   * The level-independent glyph of a range rule, where its default only means "no reading": a
   * battery sensor draws `battery`, not `battery-unknown`, while its level is a binding.
   */
  private val StableRangeIcons = mapOf("battery-unknown" to "battery")

  private val Rules: Map<String, Map<String, HaIconRule>> by lazy {
    HaIconTables.RULES.map(HaIconRule::parse)
      .groupBy { it.domain }
      .mapValues { (_, rules) -> rules.associateBy { it.deviceClass } }
  }

  private val DomainFallbacks: Map<String, String> by lazy {
    HaIconTables.DOMAIN_FALLBACKS.associate { line ->
      line.substringBefore(' ') to line.substringAfter(' ')
    }
  }

  /** One line of [HaIconTables.RULES]. */
  private class HaIconRule(
    val domain: String,
    val deviceClass: String,
    val default: String,
    val states: Map<String, String>,
    val range: List<Pair<Double, String>>,
  ) {
    val stableIcon: String = StableRangeIcons[default] ?: default

    companion object {
      fun parse(line: String): HaIconRule {
        val parts = line.split(' ')
        val states = mutableMapOf<String, String>()
        val range = mutableListOf<Pair<Double, String>>()
        for (part in parts.drop(3)) {
          val (key, icon) = part.substring(2).split('=', limit = 2)
          when (part.substring(0, 2)) {
            "s:" -> states[key] = icon
            "r:" -> range += key.toDouble() to icon
          }
        }
        return HaIconRule(parts[0], parts[1], parts[2], states, range.sortedBy { it.first })
      }
    }
  }
}

/** An entity's glyph, and its glyph while on when that differs: see [HaIconMap.entityIcons]. */
data class HaEntityIcons(val icon: ImageVector, val activeIcon: ImageVector?)
