package ee.schimke.ha.rc

import ee.schimke.ha.model.EntityState
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode

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
    val maskedFraction = Regex("^(-?\\d+)\\.[xX]+$").matchEntire(raw)
    if (maskedFraction != null) {
        val normalized = maskedFraction.groupValues[1]
        return if (unit != null) "$normalized $unit" else normalized
    }
    if (raw.toDoubleOrNull() != null) {
        val normalized = BigDecimal(raw)
            .setScale(2, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
            .trimEnd('0')
            .trimEnd('.')
            .ifEmpty { "0" }
        return if (unit != null) "$normalized $unit" else normalized
    }
    return raw.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
}
