package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.cardHeightDp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * HA `conditional` card — wraps another card and only renders it when
 * every entry in `conditions:` matches the current snapshot.
 *
 * Supported condition shorthands (each entry may use the legacy short
 * form or the explicit `condition:` discriminator):
 *
 *  - `state` (default): match `entity` against `state` (or `state_not`).
 *    Both scalars and arrays of allowed values are accepted.
 *  - `numeric_state`: match `entity` numerically against `above` /
 *    `below`. The entity's state must parse as a number.
 *
 * Other condition kinds (`screen`, `user`, `and`, `or`, …) are treated as
 * "true" so the inner card renders rather than being silently hidden.
 *
 * Height delegation needs the [CardRegistry] but [naturalHeightDp] is
 * not `@Composable`; pass the registry in if you want the inner card's
 * height when conditions match.
 */
class ConditionalCardConverter(
    private val registryForHeight: CardRegistry? = null,
) : CardConverter {
    override val cardType: String = CardTypes.CONDITIONAL

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
        if (!conditionsMet(card, snapshot)) return 0
        val inner = innerCard(card) ?: return 0
        return registryForHeight?.cardHeightDp(inner, snapshot) ?: 160
    }

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        if (!conditionsMet(card, snapshot)) return
        val inner = innerCard(card) ?: return
        RenderChild(inner, snapshot, modifier)
    }
}

private fun innerCard(card: CardConfig): CardConfig? {
    val obj = card.raw["card"] as? JsonObject ?: return null
    val type = obj["type"]?.jsonPrimitive?.content ?: return null
    return CardConfig(type = type, raw = obj)
}

private fun conditionsMet(card: CardConfig, snapshot: HaSnapshot): Boolean {
    val conditions: JsonArray = card.raw["conditions"]?.jsonArray ?: return true
    return conditions.all { el ->
        (el as? JsonObject)?.let { evaluate(it, snapshot) } ?: true
    }
}

private fun evaluate(cond: JsonObject, snapshot: HaSnapshot): Boolean {
    val kind = cond["condition"]?.jsonPrimitive?.content
        ?: if (cond.containsKey("above") || cond.containsKey("below")) "numeric_state"
        else "state"
    return when (kind) {
        "state" -> evaluateState(cond, snapshot)
        "numeric_state" -> evaluateNumeric(cond, snapshot)
        else -> true
    }
}

private fun evaluateState(cond: JsonObject, snapshot: HaSnapshot): Boolean {
    val entityId = cond["entity"]?.jsonPrimitive?.content ?: return true
    val state = snapshot.states[entityId]?.state ?: return false
    cond["state"]?.let { expected ->
        if (!matches(state, expected)) return false
    }
    cond["state_not"]?.let { forbidden ->
        if (matches(state, forbidden)) return false
    }
    return true
}

private fun evaluateNumeric(cond: JsonObject, snapshot: HaSnapshot): Boolean {
    val entityId = cond["entity"]?.jsonPrimitive?.content ?: return true
    val value = snapshot.states[entityId]?.state?.toDoubleOrNull() ?: return false
    cond["above"]?.jsonPrimitive?.content?.toDoubleOrNull()?.let { if (value <= it) return false }
    cond["below"]?.jsonPrimitive?.content?.toDoubleOrNull()?.let { if (value >= it) return false }
    return true
}

private fun matches(state: String, expected: JsonElement): Boolean = when (expected) {
    is JsonPrimitive -> state == expected.content
    is JsonArray -> expected.any { (it as? JsonPrimitive)?.content == state }
    else -> false
}
