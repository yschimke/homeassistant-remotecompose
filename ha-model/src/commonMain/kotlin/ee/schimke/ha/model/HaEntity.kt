package ee.schimke.ha.model

import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed, parsed representation of an HA entity.
 *
 * Converters switch on this sealed hierarchy instead of matching
 * `(entity.entityId.substringBefore('.'), entity.state)` strings — each subtype carries the
 * domain-appropriate sealed state type. Unknown raw values flow through as `<State>.Unknown(raw)`
 * so a new HA release never breaks the converter path.
 *
 * Domains we don't model explicitly land in [Other] with the raw [EntityState] still available for
 * string-matching fallbacks.
 */
sealed interface HaEntity {
  val raw: EntityState

  val entityId: String
    get() = raw.entityId

  val domain: String
    get() = entityId.substringBefore('.')

  val deviceClass: String?
    get() = raw.attributes["device_class"]?.jsonPrimitive?.content

  /**
   * "Is this entity in an active / on-like state?" Null means the domain doesn't have a binary
   * active concept (pure sensors, weather, etc).
   */
  val isActive: Boolean?

  data class Light(override val raw: EntityState, val state: OnOffState) : HaEntity {
    override val isActive: Boolean
      get() = state is OnOffState.On
  }

  data class Switch(override val raw: EntityState, val state: OnOffState) : HaEntity {
    override val isActive: Boolean
      get() = state is OnOffState.On
  }

  data class InputBoolean(override val raw: EntityState, val state: OnOffState) : HaEntity {
    override val isActive: Boolean
      get() = state is OnOffState.On
  }

  data class Fan(override val raw: EntityState, val state: OnOffState) : HaEntity {
    override val isActive: Boolean
      get() = state is OnOffState.On
  }

  data class Siren(override val raw: EntityState, val state: OnOffState) : HaEntity {
    override val isActive: Boolean
      get() = state is OnOffState.On
  }

  data class Humidifier(override val raw: EntityState, val state: OnOffState) : HaEntity {
    override val isActive: Boolean
      get() = state is OnOffState.On
  }

  data class Cover(override val raw: EntityState, val state: CoverState) : HaEntity {
    override val isActive: Boolean
      get() = state is CoverState.Open || state is CoverState.Opening || state is CoverState.Closing
  }

  /** "Active" means "locked" — the safe / typical resting state. */
  data class Lock(override val raw: EntityState, val state: LockState) : HaEntity {
    override val isActive: Boolean
      get() = state is LockState.Locked
  }

  data class MediaPlayer(override val raw: EntityState, val state: MediaPlayerState) : HaEntity {
    override val isActive: Boolean
      get() =
        state !is MediaPlayerState.Off &&
          state !is MediaPlayerState.Idle &&
          state !is MediaPlayerState.Standby &&
          state !is MediaPlayerState.Paused &&
          state !is MediaPlayerState.Unavailable &&
          state !is MediaPlayerState.Unknown
  }

  data class Climate(override val raw: EntityState, val mode: ClimateMode) : HaEntity {
    override val isActive: Boolean
      get() =
        mode is ClimateMode.Heat ||
          mode is ClimateMode.Cool ||
          mode is ClimateMode.HeatCool ||
          mode is ClimateMode.Auto ||
          mode is ClimateMode.Dry ||
          mode is ClimateMode.FanOnly
  }

  data class AlarmControlPanel(override val raw: EntityState, val state: AlarmState) : HaEntity {
    override val isActive: Boolean
      get() =
        state.isArmed ||
          state is AlarmState.Triggered ||
          state is AlarmState.Pending ||
          state is AlarmState.Arming
  }

  data class Vacuum(override val raw: EntityState, val state: VacuumState) : HaEntity {
    override val isActive: Boolean
      get() = state is VacuumState.Cleaning || state is VacuumState.Returning
  }

  data class Sensor(override val raw: EntityState) : HaEntity {
    override val isActive: Boolean?
      get() = null
  }

  data class BinarySensor(override val raw: EntityState, val state: OnOffState) : HaEntity {
    override val isActive: Boolean
      get() = state is OnOffState.On
  }

  data class Person(override val raw: EntityState, val state: PersonState) : HaEntity {
    override val isActive: Boolean
      get() = state is PersonState.Home
  }

  data class DeviceTracker(override val raw: EntityState, val state: PersonState) : HaEntity {
    override val isActive: Boolean
      get() = state is PersonState.Home
  }

  data class Weather(override val raw: EntityState) : HaEntity {
    override val isActive: Boolean?
      get() = null
  }

  /** Catch-all for domains without a dedicated model. */
  data class Other(override val raw: EntityState) : HaEntity {
    override val isActive: Boolean?
      get() = null
  }

  companion object {
    fun parse(raw: EntityState): HaEntity {
      val domain = raw.entityId.substringBefore('.')
      return when (domain) {
        "light" -> Light(raw, OnOffState.parse(raw.state))
        "switch" -> Switch(raw, OnOffState.parse(raw.state))
        "input_boolean" -> InputBoolean(raw, OnOffState.parse(raw.state))
        "fan" -> Fan(raw, OnOffState.parse(raw.state))
        "siren" -> Siren(raw, OnOffState.parse(raw.state))
        "humidifier" -> Humidifier(raw, OnOffState.parse(raw.state))
        "cover" -> Cover(raw, CoverState.parse(raw.state))
        "lock" -> Lock(raw, LockState.parse(raw.state))
        "media_player" -> MediaPlayer(raw, MediaPlayerState.parse(raw.state))
        "climate" -> Climate(raw, ClimateMode.parse(raw.state))
        "alarm_control_panel" -> AlarmControlPanel(raw, AlarmState.parse(raw.state))
        "vacuum" -> Vacuum(raw, VacuumState.parse(raw.state))
        "sensor" -> Sensor(raw)
        "binary_sensor" -> BinarySensor(raw, OnOffState.parse(raw.state))
        "person" -> Person(raw, PersonState.parse(raw.state))
        "device_tracker" -> DeviceTracker(raw, PersonState.parse(raw.state))
        "weather" -> Weather(raw)
        else -> Other(raw)
      }
    }
  }
}

fun EntityState.toTyped(): HaEntity = HaEntity.parse(this)
