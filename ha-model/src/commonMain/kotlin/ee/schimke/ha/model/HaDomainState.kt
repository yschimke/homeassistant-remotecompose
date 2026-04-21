package ee.schimke.ha.model

/**
 * Typed state hierarchies for the HA domains our converters care
 * about. Sealed over the known values (so `when` branches read as
 * symbolic names) but **not exhaustive on unknown values** — anything
 * we don't recognise is carried through as `Unknown(raw)` so a new
 * HA release or a custom integration won't crash the converter.
 *
 * State name reference:
 *   https://developers.home-assistant.io/docs/core/entity
 */

sealed interface OnOffState {
    data object On : OnOffState
    data object Off : OnOffState
    data object Unavailable : OnOffState
    data class Unknown(val raw: String) : OnOffState

    companion object {
        fun parse(raw: String): OnOffState = when (raw) {
            "on" -> On
            "off" -> Off
            "unavailable" -> Unavailable
            else -> Unknown(raw)
        }
    }
}

sealed interface CoverState {
    data object Closed : CoverState
    data object Closing : CoverState
    data object Opening : CoverState
    data object Open : CoverState
    data object Stopped : CoverState
    data object Unavailable : CoverState
    data class Unknown(val raw: String) : CoverState

    companion object {
        fun parse(raw: String): CoverState = when (raw) {
            "closed" -> Closed
            "closing" -> Closing
            "opening" -> Opening
            "open" -> Open
            "stopped" -> Stopped
            "unavailable" -> Unavailable
            else -> Unknown(raw)
        }
    }
}

sealed interface LockState {
    data object Locked : LockState
    data object Unlocked : LockState
    data object Locking : LockState
    data object Unlocking : LockState
    data object Jammed : LockState
    data object Open : LockState
    data object Opening : LockState
    data object Unavailable : LockState
    data class Unknown(val raw: String) : LockState

    companion object {
        fun parse(raw: String): LockState = when (raw) {
            "locked" -> Locked
            "unlocked" -> Unlocked
            "locking" -> Locking
            "unlocking" -> Unlocking
            "jammed" -> Jammed
            "open" -> Open
            "opening" -> Opening
            "unavailable" -> Unavailable
            else -> Unknown(raw)
        }
    }
}

sealed interface MediaPlayerState {
    data object Playing : MediaPlayerState
    data object Paused : MediaPlayerState
    data object Idle : MediaPlayerState
    data object Off : MediaPlayerState
    data object On : MediaPlayerState
    data object Standby : MediaPlayerState
    data object Buffering : MediaPlayerState
    data object Unavailable : MediaPlayerState
    data class Unknown(val raw: String) : MediaPlayerState

    companion object {
        fun parse(raw: String): MediaPlayerState = when (raw) {
            "playing" -> Playing
            "paused" -> Paused
            "idle" -> Idle
            "off" -> Off
            "on" -> On
            "standby" -> Standby
            "buffering" -> Buffering
            "unavailable" -> Unavailable
            else -> Unknown(raw)
        }
    }
}

sealed interface ClimateMode {
    data object Off : ClimateMode
    data object Heat : ClimateMode
    data object Cool : ClimateMode
    data object HeatCool : ClimateMode
    data object Auto : ClimateMode
    data object Dry : ClimateMode
    data object FanOnly : ClimateMode
    data object Unavailable : ClimateMode
    data class Unknown(val raw: String) : ClimateMode

    companion object {
        fun parse(raw: String): ClimateMode = when (raw) {
            "off" -> Off
            "heat" -> Heat
            "cool" -> Cool
            "heat_cool" -> HeatCool
            "auto" -> Auto
            "dry" -> Dry
            "fan_only" -> FanOnly
            "unavailable" -> Unavailable
            else -> Unknown(raw)
        }
    }
}

sealed interface AlarmState {
    data object Disarmed : AlarmState
    data object ArmedHome : AlarmState
    data object ArmedAway : AlarmState
    data object ArmedNight : AlarmState
    data object ArmedVacation : AlarmState
    data object ArmedCustomBypass : AlarmState
    data object Triggered : AlarmState
    data object Pending : AlarmState
    data object Arming : AlarmState
    data object Disarming : AlarmState
    data object Unavailable : AlarmState
    data class Unknown(val raw: String) : AlarmState

    companion object {
        fun parse(raw: String): AlarmState = when (raw) {
            "disarmed" -> Disarmed
            "armed_home" -> ArmedHome
            "armed_away" -> ArmedAway
            "armed_night" -> ArmedNight
            "armed_vacation" -> ArmedVacation
            "armed_custom_bypass" -> ArmedCustomBypass
            "triggered" -> Triggered
            "pending" -> Pending
            "arming" -> Arming
            "disarming" -> Disarming
            "unavailable" -> Unavailable
            else -> Unknown(raw)
        }
    }
}

val AlarmState.isArmed: Boolean
    get() = this is AlarmState.ArmedHome || this is AlarmState.ArmedAway ||
        this is AlarmState.ArmedNight || this is AlarmState.ArmedVacation ||
        this is AlarmState.ArmedCustomBypass

sealed interface VacuumState {
    data object Cleaning : VacuumState
    data object Docked : VacuumState
    data object Idle : VacuumState
    data object Paused : VacuumState
    data object Returning : VacuumState
    data object Error : VacuumState
    data object Unavailable : VacuumState
    data class Unknown(val raw: String) : VacuumState

    companion object {
        fun parse(raw: String): VacuumState = when (raw) {
            "cleaning" -> Cleaning
            "docked" -> Docked
            "idle" -> Idle
            "paused" -> Paused
            "returning" -> Returning
            "error" -> Error
            "unavailable" -> Unavailable
            else -> Unknown(raw)
        }
    }
}

sealed interface PersonState {
    data object Home : PersonState
    data object NotHome : PersonState
    data object Unavailable : PersonState
    data class Unknown(val raw: String) : PersonState

    companion object {
        fun parse(raw: String): PersonState = when (raw) {
            "home" -> Home
            "not_home" -> NotHome
            "unavailable" -> Unavailable
            else -> Unknown(raw)
        }
    }
}
