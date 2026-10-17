package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.model.ClimateMode
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaEntity
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.toTyped
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.haNeutralAccent
import kotlinx.serialization.json.jsonPrimitive

/**
 * Approximation of HA's `stateColor(entity)` used by `hui-tile-card` (see
 * `home-assistant/frontend/src/common/entity/state_color.ts` and the `--state-*-color` variables in
 * `src/resources/theme/color.globals.ts`).
 *
 * Semantics live on the typed [HaEntity] hierarchy — exhaustive `when` instead of scattered string
 * matching.
 *
 * An entity is in one of three looks, and they never share a colour:
 * - **active** (on, open, home, locked, …) — the domain / device-class colour from [activeFor]. A
 *   binary sensor whose device class means trouble when on (`problem`, `smoke`, `moisture`, …) is
 *   the alert red; `connectivity` is green; `motion` / `occupancy` and the rest are amber.
 * - **inactive** (off, closed, away, and any state the domain doesn't model as on, such as a binary
 *   sensor reporting `stale`) — [inactiveFor]: null, meaning the theme's neutral accent
 *   ([haNeutralAccent]). Only a lock has an inactive colour of its own (unlocked = alert red).
 * - **unavailable / unknown / missing** — neither accent: the component swaps in a neutral colour
 *   and dims the entity, off the live `<id>.is_available` binding
 *   ([HaToggleAccent.initiallyAvailable]).
 *
 * Flavors:
 * - [resolve]: static — the current state's colour, for a frozen snapshot.
 * - [activeFor] / [inactiveFor]: state-independent, paired behind an `isOn.select()` so the RC
 *   document reflects state changes at playback. [toggleAccentFor] builds that pair.
 */
object HaStateColor {
  /** Static neutral for [resolve]: an inactive entity, outside any theme. */
  val Neutral = Color(0xFFB0B0B0)

  /** Static colour for [resolve]: an unavailable / unknown entity, or no entity at all. */
  val Unavailable = Color(0xFF757575)

  /** HA's `--red-color`-family alert colour: a problem, an unlocked lock, a siren. */
  val Alert = Color(0xFFE53935)

  /** HA's `--amber-color`: a binary sensor that is on without meaning trouble (motion, …). */
  val Amber = Color(0xFFFFC107)

  /** Connected / home / safe. */
  val Green = Color(0xFF4CAF50)

  fun resolve(entity: EntityState?): Color {
    if (entity == null || !HaLiveBindings.isAvailable(entity.state)) return Unavailable
    val typed = entity.toTyped()
    return when (typed) {
      is HaEntity.Sensor,
      is HaEntity.Weather -> activeFor(entity)
      else -> if (typed.isActive == true) activeFor(entity) else inactiveFor(entity) ?: Neutral
    }
  }

  fun activeFor(entity: EntityState?): Color {
    val typed = entity?.toTyped() ?: return Unavailable
    val deviceClass = entity.attributes["device_class"]?.jsonPrimitive?.content
    return when (typed) {
      is HaEntity.Light -> Color(0xFFFFBE3E)
      is HaEntity.Switch,
      is HaEntity.InputBoolean -> Color(0xFF2196F3)
      is HaEntity.Fan -> Color(0xFF00BFA5)
      is HaEntity.Humidifier -> Color(0xFF00ACC1)
      is HaEntity.Siren -> Alert
      is HaEntity.Cover -> Color(0xFF00897B)
      is HaEntity.Lock -> Color(0xFF43A047) // locked = green (safe)
      is HaEntity.MediaPlayer -> Color(0xFF673AB7)
      is HaEntity.Climate -> climateColor(typed.mode)
      is HaEntity.AlarmControlPanel -> Color(0xFFC62828)
      is HaEntity.Vacuum -> Color(0xFF00695C)
      is HaEntity.Sensor -> sensorByDeviceClass(deviceClass) ?: Color(0xFF546E7A)
      is HaEntity.BinarySensor -> binarySensorOn(deviceClass)
      is HaEntity.Weather -> Color(0xFF0288D1)
      is HaEntity.Person,
      is HaEntity.DeviceTracker -> Green
      is HaEntity.Other -> Unavailable
    }
  }

  /**
   * The colour of the entity's inactive state, or null for the theme's neutral accent — every
   * domain but a lock, whose inactive (unlocked) state is an alert. Never a device-class colour: a
   * motion sensor that sees nothing, or a connectivity sensor that is disconnected, is not in its
   * active look.
   */
  fun inactiveFor(entity: EntityState?): Color? {
    val typed = entity?.toTyped() ?: return null
    return when (typed) {
      is HaEntity.Lock -> Alert // unlocked = red (alert)
      else -> null
    }
  }

  /**
   * The [HaToggleAccent] for [entity] (config entity id [entityId]): [activeFor] / [inactiveFor]
   * (the theme neutral when null), on/off seeded from [HaEntity.isActive], and availability from
   * the state — false for `unavailable` / `unknown`, and for a configured entity missing from the
   * snapshot. [toggleable] defaults to "the domain has an on/off concept".
   */
  @Composable
  fun toggleAccentFor(
    entityId: String?,
    entity: EntityState?,
    toggleable: Boolean = entity?.toTyped()?.isActive != null,
  ): HaToggleAccent {
    val isActive = entity?.toTyped()?.isActive
    return HaToggleAccent(
      activeAccent = activeFor(entity).rc,
      inactiveAccent = inactiveFor(entity)?.rc ?: haNeutralAccent(),
      initiallyOn = isActive ?: false,
      toggleable = toggleable,
      initiallyAvailable =
        if (entity == null) entityId == null else HaLiveBindings.isAvailable(entity.state),
    )
  }

  /**
   * The colour of HVAC mode [mode] (`heat`, `cool`, …) — the colour a climate entity in that mode
   * takes — or null where HA draws it inactive (`off`, unknown). The tile's `hvac_action` badge
   * takes the colour of the mode its action maps to (`heating` → `heat`, `idle` → `off`).
   */
  fun climateModeColor(mode: String): Color? =
    when (val parsed = ClimateMode.parse(mode)) {
      is ClimateMode.Off,
      is ClimateMode.Unavailable,
      is ClimateMode.Unknown -> null
      else -> climateColor(parsed)
    }

  private fun climateColor(mode: ClimateMode): Color =
    when (mode) {
      is ClimateMode.Heat,
      is ClimateMode.HeatCool -> Color(0xFFE65100)
      is ClimateMode.Cool -> Color(0xFF2196F3)
      is ClimateMode.Auto -> Color(0xFF546E7A)
      is ClimateMode.Dry -> Color(0xFFFDD835)
      is ClimateMode.FanOnly -> Color(0xFF00BFA5)
      is ClimateMode.Off,
      is ClimateMode.Unavailable,
      is ClimateMode.Unknown -> Neutral
    }

  /**
   * A binary sensor's on colour by device class, after HA's `--state-binary_sensor-*-on-color`: the
   * classes where `on` means something is wrong are red, the rest amber, with `connectivity` green
   * (connected) and the openings teal.
   */
  private fun binarySensorOn(deviceClass: String?): Color =
    when (deviceClass) {
      "battery", // low
      "carbon_monoxide",
      "gas",
      "heat",
      "lock", // unlocked
      "moisture", // wet
      "problem",
      "safety", // unsafe
      "smoke",
      "sound",
      "tamper",
      "update", // update available
      "vibration" -> Alert
      "connectivity" -> Green
      "door",
      "window",
      "opening",
      "garage_door",
      "garage" -> Color(0xFF00796B)
      else -> Amber // motion, occupancy, presence, plug, power, running, …
    }

  private fun sensorByDeviceClass(deviceClass: String?): Color? =
    when (deviceClass) {
      "temperature" -> Color(0xFF2196F3)
      "humidity",
      "moisture" -> Color(0xFF0288D1)
      "battery" -> Color(0xFF43A047)
      "power",
      "energy",
      "current",
      "voltage" -> Color(0xFFFFA000)
      "illuminance" -> Color(0xFFFBC02D)
      "pressure" -> Color(0xFF5E35B1)
      else -> null
    }
}
