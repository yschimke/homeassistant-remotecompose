package ee.schimke.ha.rc

import ee.schimke.ha.model.EntityState
import kotlinx.serialization.json.jsonPrimitive

/**
 * Render the user-visible state string for an entity, matching HA
 * frontend's `hui-tile-card` / `stateDisplay` behavior:
 *
 * - Numeric states keep their raw value and append the unit.
 * - Boolean / enum states capitalize the first letter.
 * - Missing entity / unknown / unavailable → "Unavailable".
 */
fun formatState(entity: EntityState?): String {
    val raw = entity?.state ?: return "Unavailable"
    if (raw == "unavailable" || raw == "unknown") return "Unavailable"
    val unit = entity.attributes["unit_of_measurement"]?.jsonPrimitive?.content
    if (raw.toDoubleOrNull() != null) {
        return if (unit != null) "$raw $unit" else raw
    }
    return raw.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
}
