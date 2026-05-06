package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Per-component data models. Each `RemoteHa*` composable takes a single
 * data parameter instead of an expanding arg list — a replacement
 * composable (custom themed tile, alternative entity row, …) can
 * consume the same model.
 *
 * Only fields that affect rendering / semantics live here; layout
 * concerns (Modifier) stay on the composable signature.
 */

/**
 * State-binding + color pair for a toggleable entity. The component
 * uses `isOn.select(activeAccent, inactiveAccent)` so a live player
 * can flip the accent without re-encoding the document. For
 * non-toggleable entities (sensors, etc.) set [isOn] = null and the
 * activeAccent is used unconditionally.
 *
 * [initiallyOn] is the boolean value at authoring time — named
 * `RemoteBoolean`s don't carry a `constantValueOrNull`, so the
 * composable needs this separately to seed an in-document
 * `MutableRemoteBoolean` for the optimistic-click pattern.
 */
data class HaToggleAccent(
    val activeAccent: RemoteColor,
    val inactiveAccent: RemoteColor,
    val isOn: RemoteBoolean? = null,
    val initiallyOn: Boolean = false,
)

/** HA tile card model. */
data class HaTileData(
    val name: RemoteString,
    val state: RemoteString,
    val icon: ImageVector,
    val accent: HaToggleAccent,
    val tapAction: HaAction = HaAction.None,
)

/** HA button card model. */
data class HaButtonData(
    val name: RemoteString,
    val icon: ImageVector,
    val accent: HaToggleAccent,
    val showName: Boolean = true,
    val tapAction: HaAction = HaAction.None,
)

/** One row in an Entities card / the entire Entity card. */
data class HaEntityRowData(
    val name: RemoteString,
    val state: RemoteString,
    val icon: ImageVector,
    val accent: HaToggleAccent,
    val tapAction: HaAction = HaAction.None,
)

/** Entities card (title + rows). The converter collects rows into this list. */
data class HaEntitiesData(
    val title: RemoteString?,
    val rows: List<HaEntityRowData>,
)

/** One cell in a Glance card. */
data class HaGlanceCellData(
    val name: RemoteString,
    val state: RemoteString,
    val icon: ImageVector,
    val accent: HaToggleAccent,
    val tapAction: HaAction = HaAction.None,
)

/** Glance card (title + cells). */
data class HaGlanceData(
    val title: RemoteString?,
    val cells: List<HaGlanceCellData>,
)

/** Markdown card. */
data class HaMarkdownData(
    val title: RemoteString?,
    val lines: List<RemoteString>,
)

/** Heading card. */
data class HaHeadingData(
    val title: RemoteString,
    val style: HaHeadingStyle = HaHeadingStyle.Title,
)

/** Unsupported-card placeholder. */
data class HaUnsupportedData(
    val cardType: RemoteString,
)

/**
 * `picture-entity` card model. We can't pull the live camera frame /
 * image entity into a `.rc` document, so the renderer emits a tinted
 * placeholder area with a domain-appropriate icon and the entity name
 * + state at the bottom — matching the visual slot the dashboard
 * expects.
 */
data class HaPictureEntityData(
    val name: RemoteString,
    val state: RemoteString,
    val icon: ImageVector,
    val accent: HaToggleAccent,
    val showName: Boolean = true,
    val showState: Boolean = true,
    val tapAction: HaAction = HaAction.None,
)

/** `gauge` card model. Half-circle dial with current value + name + range. */
data class HaGaugeData(
    val name: RemoteString,
    val valueText: RemoteString,
    val unit: String?,
    /** Current value in the gauge's [min..max] range; clamped at render time. */
    val value: Double,
    val min: Double,
    val max: Double,
    /** Severity bands sampled at the value position: green / yellow / red. */
    val severity: HaGaugeSeverity = HaGaugeSeverity.None,
    val tapAction: HaAction = HaAction.None,
)

enum class HaGaugeSeverity { None, Normal, Warning, Critical }

/** `weather-forecast` card model. */
data class HaWeatherForecastData(
    val name: RemoteString,
    val condition: RemoteString,
    /** Current temperature display, including unit. */
    val temperature: RemoteString,
    /** Optional secondary line — feels like, low/high, or extra detail. */
    val supportingLine: RemoteString?,
    val icon: ImageVector,
    val days: List<HaWeatherDay> = emptyList(),
)

/** One column in a weather forecast strip. */
data class HaWeatherDay(
    val label: RemoteString,
    val high: RemoteString,
    val low: RemoteString,
    val icon: ImageVector,
)

/**
 * `logbook` card model. Each entry is one row: name, what changed, and
 * a short "when" suffix (relative time).
 */
data class HaLogbookData(
    val title: RemoteString?,
    val entries: List<HaLogbookEntry>,
)

data class HaLogbookEntry(
    val name: RemoteString,
    val message: RemoteString,
    val whenText: RemoteString,
    val icon: ImageVector,
)

/**
 * `history-graph` card model. Each row carries the numeric samples + a
 * pre-formatted summary string ("21.4 °C", "min – max (N)") so the
 * renderer doesn't need to know about units.
 */
data class HaHistoryGraphData(
    val title: RemoteString?,
    /** "Last 24h" / "Last 12h" — whatever `hours_to_show` resolved to. */
    val rangeLabel: RemoteString,
    val rows: List<HaHistoryGraphRow>,
)

data class HaHistoryGraphRow(
    val name: RemoteString,
    val summary: RemoteString,
    val accent: Color,
    /** Numeric samples in order. Empty → renders a "no data" stub. */
    val points: List<Float>,
)

/**
 * `custom:ha-bambulab-print_status-card` model — current print job hero
 * tile. The progress ring is keyed off [progressFraction] (0f…1f);
 * ancillary fields can each be null when the snapshot doesn't have the
 * corresponding entity (the row collapses).
 */
data class HaBambuPrintStatusData(
    val printerName: RemoteString,
    val stage: RemoteString,
    val progressLabel: RemoteString,
    val progressFraction: Float,
    val accent: Color,
    val layerLine: RemoteString?,
    val remainingLine: RemoteString?,
    val nozzleLine: RemoteString?,
    val bedLine: RemoteString?,
)

/** `custom:ha-bambulab-print_control-card` — pause / resume / stop pad. */
data class HaBambuPrintControlData(
    val printerName: RemoteString,
    val pause: HaBambuControlButton?,
    val resume: HaBambuControlButton?,
    val stop: HaBambuControlButton?,
)

data class HaBambuControlButton(
    val label: RemoteString,
    val icon: ImageVector,
    val accent: Color,
    val tapAction: HaAction = HaAction.None,
)

/** `custom:ha-bambulab-ams-card` — grid of up to four filament slots. */
data class HaBambuAmsData(
    val title: RemoteString,
    val slots: List<HaBambuSpoolSlot>,
)

/** `custom:ha-bambulab-spool-card` — single filament slot, larger. */
data class HaBambuSpoolDetail(
    val slot: HaBambuSpoolSlot,
)

/** One filament tray. [color] is the spool's swatch (state.attributes.color
 *  parsed from `#RRGGBBAA`); [remainPercent] is 0..100 (or null when the
 *  HA "remain_enabled" flag is off and the integration reports nothing). */
data class HaBambuSpoolSlot(
    val slotLabel: RemoteString,
    val material: RemoteString,
    val color: Color,
    val remainPercent: Int?,
    val active: Boolean,
)

/**
 * Thermostat / humidifier / light arc-gauge model — 270° dial with a
 * filled portion up to [valueFraction], an optional target marker at
 * [targetFraction], a centred value label, and HA's mode chip.
 *
 * Used by:
 *   - thermostat → [valueFraction] = current temp normalised, [targetFraction] = setpoint, mode = "Heating"/"Cooling"/...
 *   - humidifier → same shape, value/target are humidity %, mode = "Humidifying"/"Drying"
 *   - light       → [valueFraction] = brightness, no target marker, mode = on/off label
 */
data class HaArcDialData(
    val name: RemoteString,
    val valueFraction: Float,
    val targetFraction: Float?,
    val centerLabel: RemoteString,
    /** Smaller secondary readout under the centre label (e.g. "↑ 22 °C"). */
    val supportingLabel: RemoteString?,
    val modeChip: RemoteString?,
    val accent: Color,
    val showSteppers: Boolean,
    val centerIcon: ImageVector?,
    val tapAction: HaAction = HaAction.None,
    val incrementAction: HaAction = HaAction.None,
    val decrementAction: HaAction = HaAction.None,
)

/** `clock` card model. The string is captured at capture time —
 *  alpha08 has a `RemoteAccess.getTime()` API but emitting a Compose
 *  text node bound to it requires canvas drawText; deferring live
 *  ticking to a follow-up. The host re-encodes once a minute / hour. */
data class HaClockData(
    val title: RemoteString?,
    val timeLabel: RemoteString,
    val secondaryLabel: RemoteString?,
    val isLarge: Boolean,
)

/** `statistics-graph` card model — mirrors history-graph, just with
 *  pre-aggregated mean values from HA's `recorder.statistics_during_period`.
 *  [chartType] defaults to "line"; bar mode is a follow-up. */
data class HaStatisticsGraphData(
    val title: RemoteString?,
    val rangeLabel: RemoteString,
    val rows: List<HaHistoryGraphRow>,
    val chartType: String = "line",
)

/** `alarm-panel` card model — title, status badge, ARM AWAY/HOME
 *  buttons (variants depend on `states:` config), code-input field +
 *  numeric keypad. */
data class HaAlarmPanelData(
    val title: RemoteString,
    val state: RemoteString,
    val accent: Color,
    val statusIcon: ImageVector,
    val actions: List<HaAlarmAction>,
    val showKeypad: Boolean,
)

/** One ARM button on the alarm panel — label + the call-service action
 *  it fires (typically `alarm_control_panel.alarm_arm_*`). */
data class HaAlarmAction(
    val label: RemoteString,
    val accent: Color,
    val tapAction: HaAction,
)

/** `media-control` card model — name + title/artist + transport buttons
 *  + position bar. Album-art is a placeholder swatch (alpha08 can't pull
 *  arbitrary HTTP images into a .rc document). */
data class HaMediaControlData(
    val playerName: RemoteString,
    val title: RemoteString,
    val artist: RemoteString?,
    val accent: Color,
    val isPlaying: Boolean,
    val positionFraction: Float,
    val positionLabel: RemoteString?,
    val durationLabel: RemoteString?,
    val previousAction: HaAction,
    val playPauseAction: HaAction,
    val nextAction: HaAction,
)

/** `todo-list` card model — title + active/completed item rows. */
data class HaTodoListData(
    val title: RemoteString,
    val activeItems: List<RemoteString>,
    val completedItems: List<RemoteString>,
)
