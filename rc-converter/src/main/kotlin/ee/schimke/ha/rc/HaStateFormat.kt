package ee.schimke.ha.rc

import ee.schimke.ha.model.EntityState
import kotlinx.serialization.json.jsonPrimitive

/**
 * Render the user-visible state string for an entity, matching HA
 * frontend's `hui-tile-card` / `stateDisplay` behavior:
 *
 * - Boolean-like states ("on", "off", "open", "closed", "home", "away",
 *   "unavailable", …) are capitalized.
 * - Numeric states keep their raw value and append the unit.
 * - `unknown` / `unavailable` / null entity → "Unavailable".
 */
fun formatState(entity: EntityState?): String {
    val raw = entity?.state ?: return "Unavailable"
    val unit = entity.attributes["unit_of_measurement"]?.jsonPrimitive?.content
    if (raw.toDoubleOrNull() != null) {
        return if (unit != null) "$raw $unit" else raw
    }
    return raw.replaceFirstChar { it.uppercaseChar() }
}
