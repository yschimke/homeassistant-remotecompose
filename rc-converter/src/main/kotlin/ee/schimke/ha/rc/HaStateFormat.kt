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
    val normalized = normalizeNumericValue(raw)
    if (normalized != null) return formatValueWithUnit(normalized, unit)
    return raw.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
}

internal fun formatValueWithUnit(value: String, unit: String?): String {
    val normalized = normalizeNumericValue(value) ?: value
    val cleanUnit = unit?.trim().orEmpty()
    if (cleanUnit.isEmpty()) return normalized
    return "$normalized $cleanUnit"
}

private fun normalizeNumericValue(raw: String): String? {
    val maskedFraction = Regex("^(-?\\d+)\\.[xX]+$").matchEntire(raw)
    if (maskedFraction != null) {
        return maskedFraction.groupValues[1]
    }
    if (raw.toDoubleOrNull() != null) {
        return BigDecimal(raw)
            .setScale(2, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
            .ifEmpty { "0" }
    }
    return null
}
