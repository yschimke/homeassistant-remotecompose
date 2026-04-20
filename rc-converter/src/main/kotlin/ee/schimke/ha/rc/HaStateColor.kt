package ee.schimke.ha.rc

import androidx.compose.ui.graphics.Color
import ee.schimke.ha.model.EntityState
import kotlinx.serialization.json.jsonPrimitive

/**
 * Rough approximation of HA's `stateColor(entity)` used by `hui-tile-card`
 * and friends (see `home-assistant/frontend/src/common/entity/state_color.ts`).
 *
 * Matches by domain + state; sensors with a `device_class` get a themed
 * color where HA defines one. Extend as diff visibility surfaces drift.
 */
object HaStateColor {
    private val GRAY_OFF = Color(0xFFB0B0B0)
    private val GRAY_INACTIVE = Color(0xFF757575)

    fun resolve(entity: EntityState?): Color {
        if (entity == null) return GRAY_INACTIVE
        val domain = entity.entityId.substringBefore('.')
        val state = entity.state
        val deviceClass = entity.attributes["device_class"]?.jsonPrimitive?.content

        byDeviceClass(domain, deviceClass)?.let { return it }

        return when (domain) {
            "light" -> if (state == "on") Color(0xFFFFBE3E) else GRAY_OFF
            "switch", "input_boolean" -> if (state == "on") Color(0xFF2196F3) else GRAY_OFF
            "fan" -> if (state == "on") Color(0xFF00BFA5) else GRAY_OFF
            "cover" -> if (state == "open") Color(0xFF00897B) else GRAY_OFF
            "media_player" -> if (state !in OFF_STATES) Color(0xFF673AB7) else GRAY_OFF
            "lock" -> if (state == "unlocked") Color(0xFFE53935) else Color(0xFF43A047)
            "binary_sensor" -> if (state == "on") Color(0xFFE65100) else GRAY_OFF
            "sensor" -> Color(0xFF546E7A)
            "climate" -> when (state) {
                "heat", "heat_cool" -> Color(0xFFE65100)
                "cool" -> Color(0xFF2196F3)
                "auto" -> Color(0xFF546E7A)
                else -> GRAY_OFF
            }
            "person", "device_tracker" -> if (state == "home") Color(0xFF4CAF50) else GRAY_OFF
            "weather" -> Color(0xFF0288D1)
            else -> GRAY_INACTIVE
        }
    }

    /**
     * Device-class specific accent colors for sensors / binary_sensors.
     * HA's frontend picks the same tint for "X is a temperature sensor"
     * regardless of numeric value.
     */
    private fun byDeviceClass(domain: String, deviceClass: String?): Color? {
        if (deviceClass == null) return null
        if (domain != "sensor" && domain != "binary_sensor") return null
        return when (deviceClass) {
            "temperature" -> Color(0xFF2196F3)
            "humidity", "moisture" -> Color(0xFF0288D1)
            "battery" -> Color(0xFF43A047)
            "power", "energy", "current", "voltage" -> Color(0xFFFFA000)
            "illuminance" -> Color(0xFFFBC02D)
            "pressure" -> Color(0xFF5E35B1)
            "motion", "occupancy", "presence" -> Color(0xFFE65100)
            "door", "window", "opening", "garage" -> Color(0xFF00796B)
            "connectivity" -> Color(0xFF4CAF50)
            else -> null
        }
    }

    private val OFF_STATES = setOf("off", "unavailable", "unknown", "idle", "standby")
}
