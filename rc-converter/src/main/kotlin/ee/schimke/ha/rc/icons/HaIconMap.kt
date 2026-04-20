package ee.schimke.ha.rc.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.BlindsClosed
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.EnergySavingsLeaf
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.SensorOccupied
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Window
import androidx.compose.ui.graphics.vector.ImageVector
import ee.schimke.ha.model.EntityState
import kotlinx.serialization.json.jsonPrimitive

/**
 * Map HA / MDI icon names to Compose Material icons.
 *
 * HA uses the **Material Design Icons** font (`mdi:...`) which has ~7,000
 * glyphs. Compose Material icons are **Google's Material Symbols** (~2,000
 * in `material-icons-extended`), a different family. We do not aim for
 * pixel-perfect icon parity — see the "looks like the same thing" bar in
 * project goals — but we do map common icons by meaning.
 *
 * Priority order at resolution time, matching the HA frontend:
 *   1. Explicit `icon:` on the card config (already stripped of `mdi:` prefix)
 *   2. Entity's `icon` attribute
 *   3. `device_class` default for the entity's domain
 *   4. Domain default
 *   5. Fallback `Help` question mark
 */
object HaIconMap {

    /**
     * Resolve the icon to display for an entity.
     *
     * @param iconOverride value of the card's `icon:` config, if any
     * @param entity current HA state
     * @return a Compose `ImageVector`; never null (falls back to `Help`)
     */
    fun resolve(iconOverride: String?, entity: EntityState?): ImageVector {
        iconOverride?.let { mdi(it)?.let { v -> return v } }
        entity?.attributes?.get("icon")?.jsonPrimitive?.content?.let { mdi(it)?.let { v -> return v } }

        val domain = entity?.entityId?.substringBefore('.')
        val deviceClass = entity?.attributes?.get("device_class")?.jsonPrimitive?.content
        val state = entity?.state

        byDeviceClass(domain, deviceClass, state)?.let { return it }
        byDomain(domain, state)?.let { return it }

        return Icons.Filled.Help
    }

    /** `mdi:thermometer` or `thermometer` → `Icons.Filled.Thermostat`. */
    private fun mdi(raw: String): ImageVector? {
        val key = raw.removePrefix("mdi:").removePrefix("hass:").trim().lowercase()
        return MDI_TO_COMPOSE[key]
    }

    private fun byDeviceClass(domain: String?, deviceClass: String?, state: String?): ImageVector? {
        if (domain == null || deviceClass == null) return null
        return when (domain) {
            "sensor", "binary_sensor" -> when (deviceClass) {
                "temperature" -> Icons.Filled.Thermostat
                "humidity" -> Icons.Filled.WaterDrop
                "battery" -> Icons.Filled.Battery4Bar
                "motion", "occupancy" -> Icons.Filled.SensorOccupied
                "door" -> Icons.Filled.SensorDoor
                "power", "energy" -> Icons.Filled.ElectricBolt
                "connectivity" -> Icons.Filled.Wifi
                "signal_strength" -> Icons.Filled.RssFeed
                else -> null
            }
            "cover" -> when (deviceClass) {
                "door", "garage" -> Icons.Filled.DoorFront
                "window" -> Icons.Filled.Window
                "shade", "blind", "curtain" -> Icons.Filled.BlindsClosed
                else -> null
            }
            else -> null
        }
    }

    private fun byDomain(domain: String?, state: String?): ImageVector? = when (domain) {
        "light" -> Icons.Filled.Lightbulb
        "switch", "input_boolean" -> Icons.Filled.PowerSettingsNew
        "fan" -> Icons.Filled.Air
        "climate" -> Icons.Filled.Thermostat
        "media_player" -> Icons.Filled.Speaker
        "lock" -> if (state == "unlocked") Icons.Filled.LockOpen else Icons.Filled.Lock
        "cover" -> Icons.Filled.Window
        "weather" -> Icons.Filled.Umbrella
        "person" -> Icons.Filled.Mood
        "zone" -> Icons.Filled.Home
        "automation", "script" -> Icons.Filled.PlayArrow
        "scene" -> Icons.Filled.SettingsInputComponent
        "sensor", "binary_sensor" -> Icons.Filled.Devices
        "device_tracker" -> Icons.Filled.Devices
        "input_button", "button" -> Icons.Filled.PowerSettingsNew
        "assist_satellite" -> Icons.Filled.Mic
        "conversation" -> Icons.Filled.Mic
        "media_source" -> Icons.Filled.MusicNote
        "remote" -> Icons.Filled.Tv
        "update" -> Icons.Filled.Refresh
        else -> null
    }

    /**
     * Direct `mdi:<name>` → Compose mapping for the icons that appear in
     * HA's frontend-default icon list (hui-tile-card, hui-entity-card, etc).
     * Extend as new card converters surface more icon names.
     */
    private val MDI_TO_COMPOSE: Map<String, ImageVector> = mapOf(
        "thermometer" to Icons.Filled.Thermostat,
        "thermostat" to Icons.Filled.Thermostat,
        "snowflake" to Icons.Filled.AcUnit,
        "air-conditioner" to Icons.Filled.AcUnit,
        "water-percent" to Icons.Filled.WaterDrop,
        "water" to Icons.Filled.WaterDrop,
        "battery" to Icons.Filled.Battery4Bar,
        "battery-high" to Icons.Filled.Battery4Bar,
        "lightbulb" to Icons.Filled.Lightbulb,
        "lightbulb-on" to Icons.Filled.Lightbulb,
        "ceiling-light" to Icons.Filled.Lightbulb,
        "power" to Icons.Filled.PowerSettingsNew,
        "power-plug" to Icons.Filled.PowerSettingsNew,
        "toggle-switch" to Icons.Filled.PowerSettingsNew,
        "flash" to Icons.Filled.ElectricBolt,
        "lightning-bolt" to Icons.Filled.ElectricBolt,
        "motion-sensor" to Icons.Filled.SensorOccupied,
        "walk" to Icons.Filled.SensorOccupied,
        "door" to Icons.Filled.SensorDoor,
        "door-closed" to Icons.Filled.SensorDoor,
        "door-open" to Icons.Filled.DoorFront,
        "window-closed" to Icons.Filled.Window,
        "window-open" to Icons.Filled.Window,
        "blinds" to Icons.Filled.BlindsClosed,
        "curtains" to Icons.Filled.BlindsClosed,
        "lock" to Icons.Filled.Lock,
        "lock-open" to Icons.Filled.LockOpen,
        "fan" to Icons.Filled.Air,
        "weather-cloudy" to Icons.Filled.Umbrella,
        "weather-rainy" to Icons.Filled.Umbrella,
        "home" to Icons.Filled.Home,
        "home-assistant" to Icons.Filled.Home,
        "speaker" to Icons.Filled.Speaker,
        "television" to Icons.Filled.Tv,
        "play" to Icons.Filled.PlayArrow,
        "wifi" to Icons.Filled.Wifi,
        "rss" to Icons.Filled.RssFeed,
        "microphone" to Icons.Filled.Mic,
        "leaf" to Icons.Filled.EnergySavingsLeaf,
        "emoticon-happy" to Icons.Filled.Mood,
    )
}
