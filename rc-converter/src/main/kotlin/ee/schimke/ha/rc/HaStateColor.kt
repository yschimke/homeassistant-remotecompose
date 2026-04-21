package ee.schimke.ha.rc

import androidx.compose.ui.graphics.Color
import ee.schimke.ha.model.ClimateMode
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaEntity
import ee.schimke.ha.model.OnOffState
import ee.schimke.ha.model.toTyped
import kotlinx.serialization.json.jsonPrimitive

/**
 * Approximation of HA's `stateColor(entity)` used by `hui-tile-card`
 * (see `home-assistant/frontend/src/common/entity/state_color.ts`).
 *
 * Semantics live on the typed [HaEntity] hierarchy — exhaustive
 * `when` instead of scattered string matching.
 *
 * Three flavors:
 * - [resolve]: static — return the current state's color. Good when
 *   the tile author wants a frozen snapshot and the host re-renders
 *   on state change.
 * - [activeFor]: the "active" color regardless of current state.
 *   Paired with [inactiveFor] behind an `isOn.select()` so the RC
 *   document reflects state changes at playback.
 * - [inactiveFor]: the "off" / "alert" color, also independent of
 *   current state.
 */
object HaStateColor {
    private val GrayOff = Color(0xFFB0B0B0)
    private val GrayInactive = Color(0xFF757575)

    fun resolve(entity: EntityState?): Color {
        val typed = entity?.toTyped() ?: return GrayInactive
        val deviceClass = entity.attributes["device_class"]?.jsonPrimitive?.content
        return when (typed) {
            is HaEntity.Sensor -> sensorByDeviceClass(deviceClass) ?: Color(0xFF546E7A)
            is HaEntity.BinarySensor ->
                if (typed.state is OnOffState.On) sensorByDeviceClass(deviceClass) ?: Color(0xFFE65100)
                else GrayOff
            is HaEntity.Weather -> Color(0xFF0288D1)
            is HaEntity.Person, is HaEntity.DeviceTracker ->
                if (typed.isActive == true) Color(0xFF4CAF50) else GrayOff
            else -> if (typed.isActive == true) activeFor(entity) else GrayOff
        }
    }

    fun activeFor(entity: EntityState?): Color {
        val typed = entity?.toTyped() ?: return GrayInactive
        val deviceClass = entity.attributes["device_class"]?.jsonPrimitive?.content
        return when (typed) {
            is HaEntity.Light -> Color(0xFFFFBE3E)
            is HaEntity.Switch, is HaEntity.InputBoolean -> Color(0xFF2196F3)
            is HaEntity.Fan -> Color(0xFF00BFA5)
            is HaEntity.Humidifier -> Color(0xFF00ACC1)
            is HaEntity.Siren -> Color(0xFFE53935)
            is HaEntity.Cover -> Color(0xFF00897B)
            is HaEntity.Lock -> Color(0xFF43A047)                 // locked = green (safe)
            is HaEntity.MediaPlayer -> Color(0xFF673AB7)
            is HaEntity.Climate -> climateColor(typed.mode)
            is HaEntity.AlarmControlPanel -> Color(0xFFC62828)
            is HaEntity.Vacuum -> Color(0xFF00695C)
            is HaEntity.Sensor -> sensorByDeviceClass(deviceClass) ?: Color(0xFF546E7A)
            is HaEntity.BinarySensor -> sensorByDeviceClass(deviceClass) ?: Color(0xFFE65100)
            is HaEntity.Weather -> Color(0xFF0288D1)
            is HaEntity.Person, is HaEntity.DeviceTracker -> Color(0xFF4CAF50)
            is HaEntity.Other -> GrayInactive
        }
    }

    fun inactiveFor(entity: EntityState?): Color {
        val typed = entity?.toTyped() ?: return GrayInactive
        val deviceClass = entity.attributes["device_class"]?.jsonPrimitive?.content
        return when (typed) {
            is HaEntity.Sensor -> sensorByDeviceClass(deviceClass) ?: Color(0xFF546E7A)
            is HaEntity.BinarySensor -> sensorByDeviceClass(deviceClass) ?: GrayOff
            is HaEntity.Weather -> Color(0xFF0288D1)
            is HaEntity.Lock -> Color(0xFFE53935)                 // unlocked = red (alert)
            else -> GrayOff
        }
    }

    private fun climateColor(mode: ClimateMode): Color = when (mode) {
        is ClimateMode.Heat, is ClimateMode.HeatCool -> Color(0xFFE65100)
        is ClimateMode.Cool -> Color(0xFF2196F3)
        is ClimateMode.Auto -> Color(0xFF546E7A)
        is ClimateMode.Dry -> Color(0xFFFDD835)
        is ClimateMode.FanOnly -> Color(0xFF00BFA5)
        is ClimateMode.Off, is ClimateMode.Unavailable, is ClimateMode.Unknown -> GrayOff
    }

    private fun sensorByDeviceClass(deviceClass: String?): Color? = when (deviceClass) {
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
