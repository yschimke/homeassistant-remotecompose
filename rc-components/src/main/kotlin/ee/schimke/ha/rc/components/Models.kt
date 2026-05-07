package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Per-component data models. Each `RemoteHa*` composable takes a single
 * data parameter instead of an expanding arg list — a replacement
 * composable (custom themed tile, alternative entity row, …) can
 * consume the same model.
 *
 * **Binding contract.** Fields hold plain Kotlin types — `String`,
 * `Boolean`, `Float` — that represent the *initial* value at capture
 * time. The wrapper turns each non-permanent field into a named
 * `RemoteString` / `RemoteBoolean` keyed off the data class's
 * `entityId` (e.g. `<entityId>.state`, `<entityId>.is_on`,
 * `<entityId>.attributes.<attr>`), so a running player updates by
 * pushing values by name without re-encoding the document. Permanent
 * fields (titles, configured names, range labels) stay as constants
 * in the captured bytes.
 *
 * Layout concerns (Modifier) stay on the composable signature.
 */

/**
 * State-binding + colour pair for a toggleable entity. The component
 * builds `<entityId>.is_on` and uses
 * `isOn.select(activeAccent, inactiveAccent)` so a live player can flip
 * the accent without re-encoding. For non-toggleable entities (sensors,
 * etc.) leave the parent's `entityId` null and the activeAccent is used
 * unconditionally.
 *
 * [initiallyOn] is the boolean value at authoring time — named
 * `RemoteBoolean`s don't carry a `constantValueOrNull`, so the
 * composable needs this separately to seed an in-document
 * `MutableRemoteBoolean` for the optimistic-click pattern.
 */
data class HaToggleAccent(
    val activeAccent: RemoteColor,
    val inactiveAccent: RemoteColor,
    val initiallyOn: Boolean = false,
    /** When true, no chip is drawn even if the accent flips — used by
     *  read-only displays where the toggle binding is informational. */
    val toggleable: Boolean = true,
)

/** HA tile card model. */
data class HaTileData(
    val entityId: String?,
    val name: String,
    val state: String,
    val icon: ImageVector,
    val accent: HaToggleAccent,
    val tapAction: HaAction = HaAction.None,
)

/** HA button card model. */
data class HaButtonData(
    val entityId: String?,
    val name: String,
    val icon: ImageVector,
    val accent: HaToggleAccent,
    val showName: Boolean = true,
    val tapAction: HaAction = HaAction.None,
)

/** One row in an Entities card / the entire Entity card. */
data class HaEntityRowData(
    val entityId: String?,
    val name: String,
    val state: String,
    val icon: ImageVector,
    val accent: HaToggleAccent,
    val tapAction: HaAction = HaAction.None,
)

/** Entities card (title + rows). The converter collects rows into this list. */
data class HaEntitiesData(val title: String?, val rows: List<HaEntityRowData>)

/** One cell in a Glance card. */
data class HaGlanceCellData(
    val entityId: String?,
    val name: String,
    val state: String,
    val icon: ImageVector,
    val accent: HaToggleAccent,
    val tapAction: HaAction = HaAction.None,
)

/** Glance card (title + cells). */
data class HaGlanceData(val title: String?, val cells: List<HaGlanceCellData>)

/** Markdown card. */
data class HaMarkdownData(val title: String?, val lines: List<String>)

/** Heading card. */
data class HaHeadingData(val title: String, val style: HaHeadingStyle = HaHeadingStyle.Title)

/** Unsupported-card placeholder. */
data class HaUnsupportedData(val cardType: String)

/**
 * `picture-entity` card model. We can't pull the live camera frame /
 * image entity into a `.rc` document, so the renderer emits a tinted
 * placeholder area with a domain-appropriate icon and the entity name
 * + state at the bottom — matching the visual slot the dashboard
 * expects.
 */
data class HaPictureEntityData(
    val entityId: String?,
    val name: String,
    val state: String,
    val icon: ImageVector,
    val accent: HaToggleAccent,
    val showName: Boolean = true,
    val showState: Boolean = true,
    val tapAction: HaAction = HaAction.None,
)

/** `gauge` card model. Half-circle dial with current value + name + range. */
data class HaGaugeData(
    val entityId: String?,
    val name: String,
    val valueText: String,
    val unit: String?,
    /** Current value in the gauge's [min..max] range; clamped at render time. */
    val value: Double,
    val min: Double,
    val max: Double,
    /** Severity bands sampled at the value position: green / yellow / red. */
    val severity: HaGaugeSeverity = HaGaugeSeverity.None,
    val tapAction: HaAction = HaAction.None,
)

enum class HaGaugeSeverity {
    None,
    Normal,
    Warning,
    Critical,
}

/** `weather-forecast` card model. */
data class HaWeatherForecastData(
    val entityId: String?,
    val name: String,
    val condition: String,
    /** Current temperature display, including unit. */
    val temperature: String,
    /** Optional secondary line — feels like, low/high, or extra detail. */
    val supportingLine: String?,
    val icon: ImageVector,
    val days: List<HaWeatherDay> = emptyList(),
)

/** One column in a weather forecast strip. Permanent — forecast values
 *  are captured at encode time and don't update live in alpha010. */
data class HaWeatherDay(
    val label: String,
    val high: String,
    val low: String,
    val icon: ImageVector,
)

/**
 * `logbook` card model. Each entry is one row: name, what changed, and
 * a short "when" suffix (relative time). Logbook content is permanent
 * once captured — the host re-renders by re-fetching the dashboard if
 * a new event is interesting enough to surface.
 */
data class HaLogbookData(val title: String?, val entries: List<HaLogbookEntry>)

data class HaLogbookEntry(
    val name: String,
    val message: String,
    val whenText: String,
    val icon: ImageVector,
)

/**
 * `history-graph` card model. Each row carries the numeric samples + a
 * pre-formatted summary string ("21.4 °C", "min – max (N)") so the
 * renderer doesn't need to know about units.
 */
data class HaHistoryGraphData(
    val title: String?,
    /** "Last 24h" / "Last 12h" — whatever `hours_to_show` resolved to. */
    val rangeLabel: String,
    val rows: List<HaHistoryGraphRow>,
)

data class HaHistoryGraphRow(
    val entityId: String?,
    val name: String,
    val summary: String,
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
    val entityId: String?,
    val printerName: String,
    val stage: String,
    val progressLabel: String,
    val progressFraction: Float,
    val accent: Color,
    val layerLine: String?,
    val remainingLine: String?,
    val nozzleLine: String?,
    val bedLine: String?,
)

/** `custom:ha-bambulab-print_control-card` — pause / resume / stop pad. */
data class HaBambuPrintControlData(
    val printerName: String,
    val pause: HaBambuControlButton?,
    val resume: HaBambuControlButton?,
    val stop: HaBambuControlButton?,
)

data class HaBambuControlButton(
    val label: String,
    val icon: ImageVector,
    val accent: Color,
    val tapAction: HaAction = HaAction.None,
)

/** `custom:ha-bambulab-ams-card` — grid of up to four filament slots. */
data class HaBambuAmsData(val title: String, val slots: List<HaBambuSpoolSlot>)

/** `custom:ha-bambulab-spool-card` — single filament slot, larger. */
data class HaBambuSpoolDetail(val slot: HaBambuSpoolSlot)

/** One filament tray. [color] is the spool's swatch (state.attributes.color
 *  parsed from `#RRGGBBAA`); [remainPercent] is 0..100 (or null when the
 *  HA "remain_enabled" flag is off and the integration reports nothing). */
data class HaBambuSpoolSlot(
    val entityId: String?,
    val slotLabel: String,
    val material: String,
    val color: Color,
    val remainPercent: Int?,
    val active: Boolean,
)

/** `calendar` card model — title + chronological list of upcoming
 *  events. Calendar content is permanent — the host re-renders the
 *  dashboard to surface new events. */
data class HaCalendarData(
    val title: String,
    val rangeLabel: String,
    val events: List<HaCalendarEvent>,
)

data class HaCalendarEvent(val whenLabel: String, val summary: String, val accent: Color)

/** `area` card model — area name + summary stats row + action chips
 *  (typically light/cover/fan toggles). Camera/picture attachments are
 *  the placeholder swatch (alpha08 has no area-image channel). */
data class HaAreaCardData(
    val name: String,
    val stats: List<HaAreaStat>,
    val actions: List<HaAreaAction>,
)

data class HaAreaStat(val entityId: String?, val icon: ImageVector, val label: String)

data class HaAreaAction(
    val entityId: String?,
    val icon: ImageVector,
    val accent: Color,
    val initiallyActive: Boolean,
    val tapAction: HaAction,
)

/**
 * Thermostat / humidifier / light arc-gauge model — 270° dial with a
 * filled portion up to [valueFraction], an optional target marker at
 * [targetFraction], a centred value label, and HA's mode chip.
 *
 * Per-field bindings live under `<entityId>.attributes.<attribute>`;
 * see [centerLabelAttribute] / [supportingLabelAttribute] /
 * [modeChip] for the suffix each field uses.
 */
data class HaArcDialData(
    val entityId: String?,
    val name: String,
    val valueFraction: Float,
    val targetFraction: Float?,
    val centerLabel: String,
    /** HA attribute key that drives [centerLabel] (e.g. `current_temperature_label`,
     *  `brightness_pct`). Wrapper binds `<entityId>.attributes.<this>`. */
    val centerLabelAttribute: String,
    /** Smaller secondary readout under the centre label (e.g. "↑ 22 °C"). */
    val supportingLabel: String?,
    val supportingLabelAttribute: String?,
    val modeChip: HaModeChip?,
    val accent: Color,
    val showSteppers: Boolean,
    val centerIcon: ImageVector?,
    val tapAction: HaAction = HaAction.None,
    val incrementAction: HaAction = HaAction.None,
    val decrementAction: HaAction = HaAction.None,
)

/**
 * The small mode chip rendered above the centre label of an arc dial /
 * tile. Routed through `RemoteStateLayout` for [Toggle] / [Indexed] so
 * a host flipping the underlying state toggles the visible label
 * without touching the document.
 */
sealed interface HaModeChip {
    /**
     * A pre-formatted label backed by `<entityId>.attributes.<attribute>`
     * (or constant when [entityId] is null). The player can still update
     * the text live; it can't switch between structurally different
     * variants.
     */
    data class Static(val entityId: String?, val attribute: String, val initial: String) :
        HaModeChip

    /**
     * Two label variants picked at playback time by `<entityId>.is_on`.
     * Routed through `RemoteStateLayout(RemoteBoolean)` so a host
     * flipping the boolean toggles the visible label.
     */
    data class Toggle(
        val entityId: String?,
        val initiallyOn: Boolean,
        val onLabel: String,
        val offLabel: String,
    ) : HaModeChip
}

/** `clock` card model. The default render uses
 *  `RemoteTimeDefaults.defaultTimeString` so the time text auto-ticks
 *  inside the playing document without re-encode. [staticTimeLabel]
 *  is an opt-in static override (kept for previews and for hosts that
 *  want a frozen capture).
 *
 *  When [zoneOffsetMinutes] is non-zero or [showSeconds] is true the
 *  renderer composes a live RemoteString from `RemoteContext`'s
 *  hour/minute/second floats so the player still ticks (no re-encode).
 *  [zoneOffsetMinutes] is the static offset (target zone − host zone)
 *  captured at encode time; DST transitions during playback fall to
 *  the next host re-encode. */
data class HaClockData(
    val title: String?,
    val staticTimeLabel: String?,
    val secondaryLabel: String?,
    val isLarge: Boolean,
    val use24Hour: Boolean,
    val zoneOffsetMinutes: Int = 0,
    val showSeconds: Boolean = false,
)

/** `statistics-graph` card model — mirrors history-graph, just with
 *  pre-aggregated values from HA's `recorder.statistics_during_period`.
 *  [chartType] is "line" or "bar"; [stacked] only applies to bar mode
 *  and stacks the entity contributions per bucket (HA's `stack: true`). */
data class HaStatisticsGraphData(
    val title: String?,
    val rangeLabel: String,
    val rows: List<HaHistoryGraphRow>,
    val chartType: String = "line",
    val stacked: Boolean = false,
)

/** `alarm-panel` card model — title, status badge, ARM AWAY/HOME
 *  buttons (variants depend on `states:` config), code-input field +
 *  numeric keypad. */
data class HaAlarmPanelData(
    val entityId: String?,
    val title: String,
    val state: String,
    val accent: Color,
    val statusIcon: ImageVector,
    val actions: List<HaAlarmAction>,
    val showKeypad: Boolean,
)

/** One ARM button on the alarm panel — label + the call-service action
 *  it fires (typically `alarm_control_panel.alarm_arm_*`). */
data class HaAlarmAction(val label: String, val accent: Color, val tapAction: HaAction)

/** `media-control` card model — name + title/artist + transport buttons
 *  + position bar. Album-art is a placeholder swatch (alpha08 can't pull
 *  arbitrary HTTP images into a .rc document). */
data class HaMediaControlData(
    val entityId: String?,
    val playerName: String,
    val title: String,
    val artist: String?,
    val accent: Color,
    val isPlaying: Boolean,
    val positionFraction: Float,
    val positionLabel: String?,
    val durationLabel: String?,
    val previousAction: HaAction,
    val playPauseAction: HaAction,
    val nextAction: HaAction,
)

/** `todo-list` card model — title + active/completed item rows. */
data class HaTodoListData(
    val title: String,
    val activeItems: List<HaTodoItem>,
    val completedItems: List<HaTodoItem>,
)

/** One row in a [HaTodoListData]. The [tapAction] flips the item's
 *  status via `todo.update_item`; players that don't carry a service
 *  channel back to HA will leave the row visually unchanged after a
 *  tap (no optimistic update). */
data class HaTodoItem(val summary: String, val tapAction: HaAction = HaAction.None)

/** `picture` card model — a static `image:` URL with an optional name
 *  overlay and tap target. We can't pull arbitrary HTTP images into a
 *  .rc document, so the renderer paints a tinted placeholder and shows
 *  the configured image URL as a debug caption (so a host that wires
 *  in an image-channel later doesn't need to change converters). */
data class HaPictureCardData(
    val name: String?,
    val captionUrl: String?,
    val placeholderIcon: ImageVector,
    val tapAction: HaAction = HaAction.None,
)

/** `picture-glance` card model — image with a row of clickable entity
 *  cells overlaid (typically lights / covers / switches). */
data class HaPictureGlanceData(
    val title: String?,
    val captionUrl: String?,
    val placeholderIcon: ImageVector,
    val cells: List<HaPictureGlanceCell>,
)

data class HaPictureGlanceCell(
    val entityId: String?,
    val icon: ImageVector,
    val accent: Color,
    val initiallyActive: Boolean,
    val label: String,
    val tapAction: HaAction,
)

/** `picture-elements` card model — image with arbitrarily-positioned
 *  elements. Positioned children are overlaid on the placeholder at
 *  their `(top, left)` fractions; elements without position metadata
 *  fall back to a strip across the bottom of the canvas. */
data class HaPictureElementsData(
    val captionUrl: String?,
    val placeholderIcon: ImageVector,
    val elements: List<HaPictureElement>,
)

/** Optional fractional placement of a [HaPictureElement] on the
 *  picture-elements canvas. Both axes are 0f..1f (HA's `style.top` /
 *  `style.left` percentages parsed into fractions). When null the
 *  element falls back to the strip-below layout. */
data class HaPictureElementPosition(
    val leftFraction: Float,
    val topFraction: Float,
)

sealed interface HaPictureElement {
    val position: HaPictureElementPosition?

    data class StateIcon(
        val entityId: String?,
        val icon: ImageVector,
        val accent: Color,
        val initiallyActive: Boolean,
        val tapAction: HaAction,
        override val position: HaPictureElementPosition? = null,
    ) : HaPictureElement

    data class StateLabel(
        val entityId: String?,
        val text: String,
        override val position: HaPictureElementPosition? = null,
    ) : HaPictureElement

    data class ServiceButton(
        val label: String,
        val accent: Color,
        val tapAction: HaAction,
        override val position: HaPictureElementPosition? = null,
    ) : HaPictureElement
}

/** `statistic` card model — single hero value for one entity, with
 *  optional period label and unit. Used by HA's built-in `statistic`
 *  card which surfaces one mean/min/max/sum statistic value. */
data class HaStatisticCardData(
    val entityId: String?,
    val name: String,
    val valueLabel: String,
    val unit: String?,
    val periodLabel: String?,
    val accent: Color,
)

/** `sensor` card model — entity name + big value + a small inline
 *  sparkline of the recent history. Same shape as one history-graph
 *  row, hoisted into a hero tile. */
data class HaSensorCardData(
    val entityId: String?,
    val name: String,
    val valueLabel: String,
    val accent: Color,
    val points: List<Float>,
    val rangeLabel: String?,
)
