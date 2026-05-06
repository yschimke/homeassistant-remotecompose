package ee.schimke.ha.rc.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.BlindsClosed
import androidx.compose.material.icons.filled.CleaningServices
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
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Window
import androidx.compose.ui.graphics.vector.ImageVector
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaEntity
import ee.schimke.ha.model.LockState
import ee.schimke.ha.model.toTyped
import kotlinx.serialization.json.jsonPrimitive

/**
 * Resolve the icon for an entity. Priority:
 *   1. Explicit `icon:` on the card config
 *   2. Entity's `icon` attribute
 *   3. Domain- or device-class default via [HaEntity] dispatch
 *   4. Fallback [Icons.Filled.Help]
 */
object HaIconMap {

    fun resolve(iconOverride: String?, entity: EntityState?): ImageVector {
        iconOverride?.let { mdi(it)?.let { v -> return v } }
        entity?.attributes?.get("icon")?.jsonPrimitive?.content
            ?.let { mdi(it)?.let { v -> return v } }
        val typed = entity?.toTyped() ?: return Icons.Filled.Help
        if (typed is HaEntity.Other) byDomain(entity.entityId)?.let { return it }
        return typed.defaultIcon()
    }

    /** Domain-only fallback for entity classes the typed hierarchy doesn't model
     *  (camera, scene, script, …). Keeps `mdi:` overrides authoritative. */
    private fun byDomain(entityId: String): ImageVector? = when (entityId.substringBefore('.')) {
        "camera" -> Icons.Filled.Videocam
        else -> null
    }

    private fun HaEntity.defaultIcon(): ImageVector = when (this) {
        is HaEntity.Light -> Icons.Filled.Lightbulb
        is HaEntity.Switch, is HaEntity.InputBoolean -> Icons.Filled.PowerSettingsNew
        is HaEntity.Fan -> Icons.Filled.Air
        is HaEntity.Siren -> Icons.Filled.Alarm
        is HaEntity.Humidifier -> Icons.Filled.WaterDrop
        is HaEntity.Cover -> when (deviceClass) {
            "door", "garage" -> Icons.Filled.DoorFront
            "window" -> Icons.Filled.Window
            "shade", "blind", "curtain" -> Icons.Filled.BlindsClosed
            else -> Icons.Filled.Window
        }
        is HaEntity.Lock -> if (state is LockState.Unlocked) Icons.Filled.LockOpen else Icons.Filled.Lock
        is HaEntity.MediaPlayer -> Icons.Filled.Speaker
        is HaEntity.Climate -> Icons.Filled.Thermostat
        is HaEntity.AlarmControlPanel -> Icons.Filled.Alarm
        is HaEntity.Vacuum -> Icons.Filled.CleaningServices
        is HaEntity.Sensor -> when (deviceClass) {
            "temperature" -> Icons.Filled.Thermostat
            "humidity" -> Icons.Filled.WaterDrop
            "battery" -> Icons.Filled.Battery4Bar
            "power", "energy" -> Icons.Filled.ElectricBolt
            "illuminance" -> Icons.Filled.EnergySavingsLeaf
            else -> Icons.Filled.Devices
        }
        is HaEntity.BinarySensor -> when (deviceClass) {
            "motion", "occupancy" -> Icons.Filled.SensorOccupied
            "door" -> Icons.Filled.SensorDoor
            "connectivity" -> Icons.Filled.Wifi
            else -> Icons.Filled.Devices
        }
        is HaEntity.Person, is HaEntity.DeviceTracker -> Icons.Filled.Mood
        is HaEntity.Weather -> Icons.Filled.Umbrella
        is HaEntity.Other -> Icons.Filled.Help
    }

    /** `mdi:thermometer` → `Icons.Filled.Thermostat`, or null when unmapped. */
    private fun mdi(raw: String): ImageVector? {
        val key = raw.removePrefix("mdi:").removePrefix("hass:").trim().lowercase()
        return MdiMap[key]
    }

    private val MdiMap: Map<String, ImageVector> = mapOf(
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
        "vacuum" to Icons.Filled.CleaningServices,
        "broom" to Icons.Filled.CleaningServices,
        "refresh" to Icons.Filled.Refresh,
        "cog" to Icons.Filled.SettingsInputComponent,
        "music-note" to Icons.Filled.MusicNote,
    )
}
