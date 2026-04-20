package ee.schimke.ha.rc

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.rc.components.HaTileIconStyle

/**
 * Pick the tile's icon treatment using roughly the same rule HA does:
 * toggle-like entities get a filled-circle chip behind the icon; pure
 * read-only sensors render the icon glyph plain.
 *
 * See `home-assistant/frontend/src/panels/lovelace/cards/hui-tile-card.ts`
 * (and `stateColorCss` / `hasActiveColor`) for the reference rule.
 */
fun tileIconStyleFor(entity: EntityState?): HaTileIconStyle {
    if (entity == null) return HaTileIconStyle.Plain
    val domain = entity.entityId.substringBefore('.')
    val state = entity.state
    return when (domain) {
        // Always-plain domains — read-only or time-like.
        "sensor", "binary_sensor", "weather", "device_tracker", "person",
        "sun", "update", "calendar", "camera", "conversation", "tts",
            -> HaTileIconStyle.Plain

        // Toggleable domains — chip when "active", plain when off/unavailable.
        "light", "switch", "input_boolean", "fan", "siren",
            -> if (state == "on") HaTileIconStyle.Chip else HaTileIconStyle.Plain

        "cover" -> if (state == "open" || state == "opening" || state == "closing") HaTileIconStyle.Chip else HaTileIconStyle.Plain
        "lock" -> HaTileIconStyle.Chip
        "media_player" -> if (state !in OFF_STATES) HaTileIconStyle.Chip else HaTileIconStyle.Plain
        "climate" -> if (state in setOf("heat", "cool", "heat_cool", "auto", "dry", "fan_only")) HaTileIconStyle.Chip else HaTileIconStyle.Plain
        "humidifier" -> if (state == "on") HaTileIconStyle.Chip else HaTileIconStyle.Plain
        "alarm_control_panel" -> if (state in setOf("armed_home", "armed_away", "armed_night", "armed_vacation", "armed_custom_bypass", "triggered", "pending", "arming")) HaTileIconStyle.Chip else HaTileIconStyle.Plain
        "vacuum" -> if (state == "cleaning" || state == "returning") HaTileIconStyle.Chip else HaTileIconStyle.Plain

        else -> HaTileIconStyle.Plain
    }
}

private val OFF_STATES = setOf("off", "unavailable", "unknown", "idle", "standby", "paused")
