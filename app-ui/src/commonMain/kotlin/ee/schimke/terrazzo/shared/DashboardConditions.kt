package ee.schimke.terrazzo.shared

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import kotlinx.serialization.json.*

/** Null means this host cannot evaluate a condition, never permission to show its content. */
internal fun dashboardConditions(conditions: JsonElement?, snapshot: HaSnapshot): Boolean? {
  val list = conditions as? JsonArray ?: return null
  val results = list.map { dashboardCondition(it as? JsonObject ?: return null, snapshot) }
  return when {
    false in results -> false
    null in results -> null
    else -> true
  }
}

private fun dashboardCondition(condition: JsonObject, snapshot: HaSnapshot): Boolean? {
  fun text(key: String) = (condition[key] as? JsonPrimitive)?.contentOrNull
  val kind =
    text("condition")
      ?: if ("above" in condition || "below" in condition) "numeric_state" else "state"
  if (kind in setOf("and", "or", "not")) {
    if (condition.keys.any { it !in setOf("condition", "conditions") }) return null
    val children = condition["conditions"] as? JsonArray ?: return null
    if (kind != "or") {
      val result = dashboardConditions(children, snapshot)
      return if (kind == "not") result?.not() else result
    }
    val results = children.map { dashboardCondition(it as? JsonObject ?: return null, snapshot) }
    return when {
      true in results -> true
      null in results -> null
      else -> false
    }
  }
  // Automation-only options (duration, templates, attributes, etc.) need HA's evaluator.
  val supported = setOf("condition", "entity", "entity_id", "state", "state_not", "above", "below")
  if (condition.keys.any { it !in supported }) return null
  val id = text("entity") ?: text("entity_id") ?: return null
  val state = snapshot.states[id]?.state ?: return false
  fun matches(expected: JsonElement): Boolean? =
    when (expected) {
      is JsonPrimitive -> state == expected.content
      is JsonArray ->
        if (expected.all { it is JsonPrimitive }) expected.any { state == it.jsonPrimitive.content }
        else null
      else -> null
    }
  return when (kind) {
    "state" -> {
      if ("state" !in condition && "state_not" !in condition) return null
      val equal = condition["state"]?.let { matches(it) ?: return null } ?: true
      val unequal = condition["state_not"]?.let { !(matches(it) ?: return null) } ?: true
      equal && unequal
    }
    "numeric_state" -> {
      if ("above" !in condition && "below" !in condition) return null
      val value = state.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return false
      fun threshold(key: String): Double? {
        val raw = text(key) ?: return null
        return (raw.toDoubleOrNull() ?: snapshot.states[raw]?.state?.toDoubleOrNull())?.takeIf {
          it.isFinite()
        }
      }
      val above = if ("above" in condition) threshold("above") ?: return false else null
      val below = if ("below" in condition) threshold("below") ?: return false else null
      (above == null || value > above) && (below == null || value < below)
    }
    else -> null
  }
}

/** Resolve nested wrappers before packing cells, so hidden cards leave no empty grid slots. */
internal fun visibleDashboardCard(card: CardConfig, snapshot: HaSnapshot): CardConfig? {
  if (card.type != "conditional") return card
  return when (dashboardConditions(card.raw["conditions"], snapshot)) {
    false -> null
    null -> card // The leaf renderer explains the unsupported condition without showing its child.
    true -> {
      val raw = card.raw["card"] as? JsonObject ?: return card
      val type = (raw["type"] as? JsonPrimitive)?.contentOrNull ?: return card
      visibleDashboardCard(CardConfig(type, raw), snapshot)
    }
  }
}
