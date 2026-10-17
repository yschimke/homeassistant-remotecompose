@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaAlarmAction
import ee.schimke.ha.rc.components.HaAlarmPanelData
import ee.schimke.ha.rc.components.HaAlarmStatus
import ee.schimke.ha.rc.components.HaArcDialData
import ee.schimke.ha.rc.components.HaAreaAction
import ee.schimke.ha.rc.components.HaAreaCardData
import ee.schimke.ha.rc.components.HaAreaStat
import ee.schimke.ha.rc.components.HaBambuAmsData
import ee.schimke.ha.rc.components.HaBambuControlButton
import ee.schimke.ha.rc.components.HaBambuPrintControlData
import ee.schimke.ha.rc.components.HaBambuPrintPhase
import ee.schimke.ha.rc.components.HaBambuPrintStatusData
import ee.schimke.ha.rc.components.HaBambuSpoolDetail
import ee.schimke.ha.rc.components.HaBambuSpoolSlot
import ee.schimke.ha.rc.components.HaCalendarData
import ee.schimke.ha.rc.components.HaCalendarEvent
import ee.schimke.ha.rc.components.HaClockData
import ee.schimke.ha.rc.components.HaGaugeBands
import ee.schimke.ha.rc.components.HaGaugeData
import ee.schimke.ha.rc.components.HaHistoryGraphData
import ee.schimke.ha.rc.components.HaHistoryGraphRow
import ee.schimke.ha.rc.components.HaLogbookData
import ee.schimke.ha.rc.components.HaLogbookEntry
import ee.schimke.ha.rc.components.HaMediaControlData
import ee.schimke.ha.rc.components.HaModeChip
import ee.schimke.ha.rc.components.HaPictureCardData
import ee.schimke.ha.rc.components.HaPictureElement
import ee.schimke.ha.rc.components.HaPictureElementPosition
import ee.schimke.ha.rc.components.HaPictureElementsData
import ee.schimke.ha.rc.components.HaPictureEntityData
import ee.schimke.ha.rc.components.HaPictureGlanceCell
import ee.schimke.ha.rc.components.HaPictureGlanceData
import ee.schimke.ha.rc.components.HaSensorCardData
import ee.schimke.ha.rc.components.HaStatisticCardData
import ee.schimke.ha.rc.components.HaStatisticsGraphData
import ee.schimke.ha.rc.components.HaTodoItem
import ee.schimke.ha.rc.components.HaTodoListData
import ee.schimke.ha.rc.components.HaWeatherDay
import ee.schimke.ha.rc.components.HaWeatherExtra
import ee.schimke.ha.rc.components.HaWeatherForecastData
import ee.schimke.ha.rc.components.LiveValues

/*
 * Tier-2 plain-data models for every card beyond the core set in HaUiModels.kt.
 *
 * Each `Ha*UiData` mirrors one Tier-1 `Ha*Data` model field-for-field, with:
 *
 * - `RemoteString` / `RemoteFloat` fields as `String` / `Float` (mapped to constants);
 * - every `entityId` (and other binding-only key such as an attribute name) dropped — a Tier-2 card
 *   has no live value stream, so the Tier-1 model always gets `entityId = null` and falls back to
 *   constants, exactly like [HaTileUiData];
 * - nested Tier-1 models mirrored as nested `*UiData` classes, so the public Tier-2 surface is
 *   self-contained plain data (only [HaAction] and the plain enums are shared with Tier-1).
 */

// ——— layout variants ———

/** Which Tier-1 gauge layout [HaGauge] draws. */
enum class HaGaugeStyle {
  /** `RemoteHaGauge` — half-circle dial, value centred under it. */
  Standard,
  /** `RemoteHaGaugeStacked` — dial above, name and value stacked below. */
  Stacked,
  /** `RemoteHaGaugeWide` — dial and text side by side, for short wide slots. */
  Wide,
}

/** Which Tier-1 arc-dial layout [HaArcDial] draws. */
enum class HaArcDialStyle {
  /** `RemoteHaArcDial` — 270° dial with the labels inside. */
  Standard,
  /** `RemoteHaArcDialWide` — dial and labels in a row. */
  Wide,
}

/** Standard vs. wide (row) layout, for cards whose Tier-1 API has exactly those two. */
enum class HaCardLayout {
  Standard,
  Wide,
}

/** `statistics-graph` chart type; Tier-1 carries it as the HA string `"line"` / `"bar"`. */
enum class HaStatisticsChartType(internal val haValue: String) {
  Line("line"),
  Bar("bar"),
}

// ——— gauge ———

/** Plain mirror of [HaGaugeBands]. */
data class HaGaugeBandsUiData(
  val green: Double? = null,
  val yellow: Double? = null,
  val red: Double? = null,
)

/** Plain mirror of [HaGaugeData]. */
data class HaGaugeUiData(
  val name: String,
  val valueText: String,
  val unit: String?,
  val value: Float,
  val min: Double,
  val max: Double,
  val severity: HaGaugeBandsUiData = HaGaugeBandsUiData(),
  val tapAction: HaAction = HaAction.None,
)

// ——— arc dial (thermostat / humidifier / light) ———

/** Plain mirror of the sealed [HaModeChip]. */
sealed interface HaModeChipUiData {
  /** A fixed label. */
  data class Static(val initial: String) : HaModeChipUiData

  /** Two label variants; [initiallyOn] picks which one is shown. */
  data class Toggle(val initiallyOn: Boolean, val onLabel: String, val offLabel: String) :
    HaModeChipUiData
}

/** Plain mirror of [HaArcDialData] — thermostat, humidifier and light cards. */
data class HaArcDialUiData(
  val name: String,
  val valueFraction: Float,
  val targetFraction: Float?,
  val centerLabel: String,
  val supportingLabel: String? = null,
  val modeChip: HaModeChipUiData? = null,
  val accent: Color,
  val showSteppers: Boolean = false,
  val centerIcon: ImageVector? = null,
  val tapAction: HaAction = HaAction.None,
  val incrementAction: HaAction = HaAction.None,
  val decrementAction: HaAction = HaAction.None,
)

// ——— history graph / statistics graph / statistic / sensor ———

/** Plain mirror of [HaHistoryGraphRow]. */
data class HaHistoryGraphRowUiData(
  val name: String,
  val summary: String,
  val accent: Color,
  val points: List<Float>,
)

/** Plain mirror of [HaHistoryGraphData]. */
data class HaHistoryGraphUiData(
  val title: String?,
  val rangeLabel: String,
  val rows: List<HaHistoryGraphRowUiData>,
)

/** Plain mirror of [HaStatisticsGraphData]. */
data class HaStatisticsGraphUiData(
  val title: String?,
  val rangeLabel: String,
  val rows: List<HaHistoryGraphRowUiData>,
  val chartType: HaStatisticsChartType = HaStatisticsChartType.Line,
  val stacked: Boolean = false,
)

/** Plain mirror of [HaStatisticCardData]. */
data class HaStatisticCardUiData(
  val name: String,
  val valueLabel: String,
  val unit: String?,
  val periodLabel: String?,
  val accent: Color,
)

/** Plain mirror of [HaSensorCardData]. */
data class HaSensorCardUiData(
  val name: String,
  val valueLabel: String,
  val accent: Color,
  val points: List<Float>,
  val rangeLabel: String?,
)

// ——— logbook / calendar / todo ———

/** Plain mirror of [HaLogbookEntry]. */
data class HaLogbookEntryUiData(
  val name: String,
  val message: String,
  val whenText: String,
  val icon: ImageVector,
)

/** Plain mirror of [HaLogbookData]. */
data class HaLogbookUiData(val title: String?, val entries: List<HaLogbookEntryUiData>)

/** Plain mirror of [HaCalendarEvent]. */
data class HaCalendarEventUiData(val whenLabel: String, val summary: String, val accent: Color)

/** Plain mirror of [HaCalendarData]. */
data class HaCalendarUiData(
  val title: String,
  val rangeLabel: String,
  val events: List<HaCalendarEventUiData>,
)

/** Plain mirror of [HaTodoItem]. */
data class HaTodoItemUiData(val summary: String, val tapAction: HaAction = HaAction.None)

/** Plain mirror of [HaTodoListData]. */
data class HaTodoListUiData(
  val title: String,
  val activeItems: List<HaTodoItemUiData>,
  val completedItems: List<HaTodoItemUiData> = emptyList(),
)

// ——— weather ———

/** Plain mirror of [HaWeatherDay]. */
data class HaWeatherDayUiData(
  val label: String,
  val high: String,
  val low: String,
  val icon: ImageVector,
)

/** Plain mirror of [HaWeatherExtra]. */
data class HaWeatherExtraUiData(val icon: ImageVector, val label: String, val value: String)

/** Plain mirror of [HaWeatherForecastData]. */
data class HaWeatherForecastUiData(
  val name: String,
  val condition: String,
  val temperature: String,
  val supportingLine: String?,
  val highLow: String? = null,
  val icon: ImageVector,
  val days: List<HaWeatherDayUiData> = emptyList(),
  val extras: List<HaWeatherExtraUiData> = emptyList(),
)

// ——— media control ———

/** Plain mirror of [HaMediaControlData]. */
data class HaMediaControlUiData(
  val playerName: String,
  val title: String,
  val artist: String?,
  val accent: Color,
  val isPlaying: Boolean,
  val positionFraction: Float,
  val positionLabel: String?,
  val durationLabel: String?,
  val previousAction: HaAction = HaAction.None,
  val playPauseAction: HaAction = HaAction.None,
  val nextAction: HaAction = HaAction.None,
)

// ——— alarm panel ———

/** Plain mirror of [HaAlarmStatus]. */
data class HaAlarmStatusUiData(
  val stateKey: Int,
  val label: String,
  val accent: Color,
  val icon: ImageVector,
)

/** Plain mirror of [HaAlarmAction]. */
data class HaAlarmActionUiData(val label: String, val accent: Color, val tapAction: HaAction)

/**
 * Plain mirror of [HaAlarmPanelData]. [initialStateInt] selects the visible entry of [statuses] by
 * its [HaAlarmStatusUiData.stateKey]; the same invariants as the Tier-1 model apply.
 */
data class HaAlarmPanelUiData(
  val title: String,
  val initialStateInt: Int,
  val statuses: List<HaAlarmStatusUiData>,
  val actions: List<HaAlarmActionUiData>,
  val showKeypad: Boolean,
  val codeLength: Int? = null,
) {
  init {
    require(statuses.isNotEmpty()) { "HaAlarmPanelUiData.statuses must not be empty" }
    require(statuses.distinctBy { it.stateKey }.size == statuses.size) {
      "HaAlarmPanelUiData.statuses must have distinct stateKey values"
    }
  }
}

// ——— area ———

/** Plain mirror of [HaAreaStat]. */
data class HaAreaStatUiData(val icon: ImageVector, val label: String)

/** Plain mirror of [HaAreaAction]. */
data class HaAreaActionUiData(
  val icon: ImageVector,
  val accent: Color,
  val initiallyActive: Boolean,
  val tapAction: HaAction = HaAction.None,
)

/** Plain mirror of [HaAreaCardData]. */
data class HaAreaCardUiData(
  val name: String,
  val stats: List<HaAreaStatUiData>,
  val actions: List<HaAreaActionUiData> = emptyList(),
)

// ——— clock ———

/** Plain mirror of [HaClockData]. */
data class HaClockUiData(
  val title: String?,
  val staticTimeLabel: String?,
  val secondaryLabel: String?,
  val isLarge: Boolean = false,
  val use24Hour: Boolean = true,
  val zoneOffsetMinutes: Int = 0,
  val showSeconds: Boolean = false,
)

// ——— picture family ———

/** Plain mirror of [HaPictureCardData]. */
data class HaPictureCardUiData(
  val name: String?,
  val captionUrl: String?,
  val placeholderIcon: ImageVector,
  val tapAction: HaAction = HaAction.None,
)

/** Plain mirror of [HaPictureGlanceCell]. */
data class HaPictureGlanceCellUiData(
  val icon: ImageVector,
  val accent: Color,
  val initiallyActive: Boolean,
  val label: String,
  val tapAction: HaAction = HaAction.None,
)

/** Plain mirror of [HaPictureGlanceData]. */
data class HaPictureGlanceUiData(
  val title: String?,
  val captionUrl: String?,
  val placeholderIcon: ImageVector,
  val cells: List<HaPictureGlanceCellUiData>,
)

/** Plain mirror of [HaPictureElementPosition]; both axes are 0f..1f fractions of the canvas. */
data class HaPictureElementPositionUiData(val leftFraction: Float, val topFraction: Float)

/** Plain mirror of the sealed [HaPictureElement]. */
sealed interface HaPictureElementUiData {
  val position: HaPictureElementPositionUiData?

  data class StateIcon(
    val icon: ImageVector,
    val accent: Color,
    val initiallyActive: Boolean,
    val tapAction: HaAction = HaAction.None,
    override val position: HaPictureElementPositionUiData? = null,
  ) : HaPictureElementUiData

  data class StateLabel(
    val text: String,
    override val position: HaPictureElementPositionUiData? = null,
  ) : HaPictureElementUiData

  data class ServiceButton(
    val label: String,
    val accent: Color,
    val tapAction: HaAction,
    override val position: HaPictureElementPositionUiData? = null,
  ) : HaPictureElementUiData
}

/** Plain mirror of [HaPictureElementsData]. */
data class HaPictureElementsUiData(
  val captionUrl: String?,
  val placeholderIcon: ImageVector,
  val elements: List<HaPictureElementUiData>,
)

/**
 * Plain mirror of [HaPictureEntityData]. There is no `imageUrl`: the Tier-2 host plays documents
 * without a `BitmapLoader`, so a Tier-2 picture-entity always draws the icon placeholder.
 */
data class HaPictureEntityUiData(
  val name: String,
  val state: String,
  val icon: ImageVector,
  val accent: HaToggleAccentUi,
  val showName: Boolean = true,
  val showState: Boolean = true,
  val frameStamp: String? = null,
  val tapAction: HaAction = HaAction.None,
)

// ——— Bambu Lab ———

/** Plain mirror of [HaBambuPrintStatusData]. */
data class HaBambuPrintStatusUiData(
  val printerName: String,
  val stage: String,
  val progressLabel: String,
  val progressFraction: Float,
  val accent: Color,
  val layerLine: String? = null,
  val remainingLine: String? = null,
  val nozzleLine: String? = null,
  val bedLine: String? = null,
  val phase: HaBambuPrintPhase = HaBambuPrintPhase.Printing,
)

/** Plain mirror of [HaBambuControlButton]. */
data class HaBambuControlButtonUiData(
  val label: String,
  val icon: ImageVector,
  val accent: Color,
  val tapAction: HaAction = HaAction.None,
)

/** Plain mirror of [HaBambuPrintControlData]. */
data class HaBambuPrintControlUiData(
  val printerName: String,
  val pause: HaBambuControlButtonUiData?,
  val resume: HaBambuControlButtonUiData?,
  val stop: HaBambuControlButtonUiData?,
)

/** Plain mirror of [HaBambuSpoolSlot]. */
data class HaBambuSpoolSlotUiData(
  val slotLabel: String,
  val material: String,
  val color: Color,
  val remainPercent: Int?,
  val active: Boolean,
)

/** Plain mirror of [HaBambuAmsData]. */
data class HaBambuAmsUiData(val title: String, val slots: List<HaBambuSpoolSlotUiData>)

/** Plain mirror of [HaBambuSpoolDetail]. */
data class HaBambuSpoolDetailUiData(val slot: HaBambuSpoolSlotUiData)

// ——— plain → Remote bridges ———

internal fun HaGaugeBandsUiData.toRemote(): HaGaugeBands =
  HaGaugeBands(green = green, yellow = yellow, red = red)

internal fun HaGaugeUiData.toRemote(): HaGaugeData =
  HaGaugeData(
    entityId = null,
    name = name,
    valueText = LiveValues.state(null, valueText),
    unit = unit,
    value = LiveValues.numericState(null, value),
    min = min,
    max = max,
    severity = severity.toRemote(),
    tapAction = tapAction,
  )

internal fun HaModeChipUiData.toRemote(): HaModeChip =
  when (this) {
    is HaModeChipUiData.Static ->
      HaModeChip.Static(entityId = null, attribute = MODE_CHIP_ATTRIBUTE, initial = initial)
    is HaModeChipUiData.Toggle ->
      HaModeChip.Toggle(
        entityId = null,
        initiallyOn = initiallyOn,
        onLabel = onLabel,
        offLabel = offLabel,
      )
  }

// Binding-only attribute keys. With `entityId = null` they never name a binding, but the Tier-1
// arc dial only draws the supporting label when its attribute key is non-null.
private const val MODE_CHIP_ATTRIBUTE = "mode_label"
private const val CENTER_LABEL_ATTRIBUTE = "center_label"
private const val SUPPORTING_LABEL_ATTRIBUTE = "supporting_label"

internal fun HaArcDialUiData.toRemote(): HaArcDialData =
  HaArcDialData(
    entityId = null,
    name = name,
    valueFraction = valueFraction,
    targetFraction = targetFraction,
    centerLabel = centerLabel,
    centerLabelAttribute = CENTER_LABEL_ATTRIBUTE,
    supportingLabel = supportingLabel,
    supportingLabelAttribute = supportingLabel?.let { SUPPORTING_LABEL_ATTRIBUTE },
    modeChip = modeChip?.toRemote(),
    accent = accent,
    showSteppers = showSteppers,
    centerIcon = centerIcon,
    tapAction = tapAction,
    incrementAction = incrementAction,
    decrementAction = decrementAction,
  )

internal fun HaHistoryGraphRowUiData.toRemote(): HaHistoryGraphRow =
  HaHistoryGraphRow(
    entityId = null,
    name = name,
    summary = LiveValues.state(null, summary),
    accent = accent,
    points = points,
  )

internal fun HaHistoryGraphUiData.toRemote(): HaHistoryGraphData =
  HaHistoryGraphData(title = title, rangeLabel = rangeLabel, rows = rows.map { it.toRemote() })

internal fun HaStatisticsGraphUiData.toRemote(): HaStatisticsGraphData =
  HaStatisticsGraphData(
    title = title,
    rangeLabel = rangeLabel,
    rows = rows.map { it.toRemote() },
    chartType = chartType.haValue,
    stacked = stacked,
  )

internal fun HaStatisticCardUiData.toRemote(): HaStatisticCardData =
  HaStatisticCardData(
    entityId = null,
    name = name,
    valueLabel = LiveValues.state(null, valueLabel),
    unit = unit,
    periodLabel = periodLabel,
    accent = accent,
  )

internal fun HaSensorCardUiData.toRemote(): HaSensorCardData =
  HaSensorCardData(
    entityId = null,
    name = name,
    valueLabel = valueLabel,
    accent = accent,
    points = points,
    rangeLabel = rangeLabel,
  )

internal fun HaLogbookEntryUiData.toRemote(): HaLogbookEntry =
  HaLogbookEntry(name = name, message = message, whenText = whenText, icon = icon)

internal fun HaLogbookUiData.toRemote(): HaLogbookData =
  HaLogbookData(title = title, entries = entries.map { it.toRemote() })

internal fun HaCalendarEventUiData.toRemote(): HaCalendarEvent =
  HaCalendarEvent(whenLabel = whenLabel, summary = summary, accent = accent)

internal fun HaCalendarUiData.toRemote(): HaCalendarData =
  HaCalendarData(title = title, rangeLabel = rangeLabel, events = events.map { it.toRemote() })

internal fun HaTodoItemUiData.toRemote(): HaTodoItem =
  HaTodoItem(summary = summary, tapAction = tapAction)

internal fun HaTodoListUiData.toRemote(): HaTodoListData =
  HaTodoListData(
    title = title,
    activeItems = activeItems.map { it.toRemote() },
    completedItems = completedItems.map { it.toRemote() },
  )

internal fun HaWeatherDayUiData.toRemote(): HaWeatherDay =
  HaWeatherDay(label = label, high = high, low = low, icon = icon)

internal fun HaWeatherExtraUiData.toRemote(): HaWeatherExtra =
  HaWeatherExtra(icon = icon, label = label, value = value)

internal fun HaWeatherForecastUiData.toRemote(): HaWeatherForecastData =
  HaWeatherForecastData(
    entityId = null,
    name = name,
    condition = LiveValues.state(null, condition),
    temperature = temperature,
    supportingLine = supportingLine,
    highLow = highLow,
    icon = icon,
    days = days.map { it.toRemote() },
    extras = extras.map { it.toRemote() },
  )

internal fun HaMediaControlUiData.toRemote(): HaMediaControlData =
  HaMediaControlData(
    entityId = null,
    playerName = playerName,
    title = title,
    artist = artist,
    accent = accent,
    isPlaying = isPlaying,
    positionFraction = positionFraction,
    positionLabel = positionLabel,
    durationLabel = durationLabel,
    previousAction = previousAction,
    playPauseAction = playPauseAction,
    nextAction = nextAction,
  )

internal fun HaAlarmStatusUiData.toRemote(): HaAlarmStatus =
  HaAlarmStatus(stateKey = stateKey, label = label, accent = accent, icon = icon)

internal fun HaAlarmActionUiData.toRemote(): HaAlarmAction =
  HaAlarmAction(label = label, accent = accent, tapAction = tapAction)

internal fun HaAlarmPanelUiData.toRemote(): HaAlarmPanelData =
  HaAlarmPanelData(
    entityId = null,
    title = title,
    initialStateInt = initialStateInt,
    statuses = statuses.map { it.toRemote() },
    actions = actions.map { it.toRemote() },
    showKeypad = showKeypad,
    codeLength = codeLength,
  )

internal fun HaAreaStatUiData.toRemote(): HaAreaStat =
  HaAreaStat(icon = icon, label = LiveValues.state(null, label))

internal fun HaAreaActionUiData.toRemote(): HaAreaAction =
  HaAreaAction(
    entityId = null,
    icon = icon,
    accent = accent,
    initiallyActive = initiallyActive,
    tapAction = tapAction,
  )

internal fun HaAreaCardUiData.toRemote(): HaAreaCardData =
  HaAreaCardData(
    name = name,
    stats = stats.map { it.toRemote() },
    actions = actions.map { it.toRemote() },
  )

internal fun HaClockUiData.toRemote(): HaClockData =
  HaClockData(
    title = title,
    staticTimeLabel = staticTimeLabel,
    secondaryLabel = secondaryLabel,
    isLarge = isLarge,
    use24Hour = use24Hour,
    zoneOffsetMinutes = zoneOffsetMinutes,
    showSeconds = showSeconds,
  )

internal fun HaPictureCardUiData.toRemote(): HaPictureCardData =
  HaPictureCardData(
    name = name,
    captionUrl = captionUrl,
    placeholderIcon = placeholderIcon,
    tapAction = tapAction,
  )

internal fun HaPictureGlanceCellUiData.toRemote(): HaPictureGlanceCell =
  HaPictureGlanceCell(
    entityId = null,
    icon = icon,
    accent = accent,
    initiallyActive = initiallyActive,
    label = label,
    tapAction = tapAction,
  )

internal fun HaPictureGlanceUiData.toRemote(): HaPictureGlanceData =
  HaPictureGlanceData(
    title = title,
    captionUrl = captionUrl,
    placeholderIcon = placeholderIcon,
    cells = cells.map { it.toRemote() },
  )

internal fun HaPictureElementPositionUiData.toRemote(): HaPictureElementPosition =
  HaPictureElementPosition(leftFraction = leftFraction, topFraction = topFraction)

internal fun HaPictureElementUiData.toRemote(): HaPictureElement =
  when (this) {
    is HaPictureElementUiData.StateIcon ->
      HaPictureElement.StateIcon(
        entityId = null,
        icon = icon,
        accent = accent,
        initiallyActive = initiallyActive,
        tapAction = tapAction,
        position = position?.toRemote(),
      )
    is HaPictureElementUiData.StateLabel ->
      HaPictureElement.StateLabel(
        entityId = null,
        text = LiveValues.state(null, text),
        position = position?.toRemote(),
      )
    is HaPictureElementUiData.ServiceButton ->
      HaPictureElement.ServiceButton(
        label = label,
        accent = accent,
        tapAction = tapAction,
        position = position?.toRemote(),
      )
  }

internal fun HaPictureElementsUiData.toRemote(): HaPictureElementsData =
  HaPictureElementsData(
    captionUrl = captionUrl,
    placeholderIcon = placeholderIcon,
    elements = elements.map { it.toRemote() },
  )

internal fun HaPictureEntityUiData.toRemote(): HaPictureEntityData =
  HaPictureEntityData(
    entityId = null,
    name = name,
    state = LiveValues.state(null, state),
    icon = icon,
    accent = accent.toRemote(),
    showName = showName,
    showState = showState,
    imageUrl = null,
    frameStamp = frameStamp,
    tapAction = tapAction,
  )

internal fun HaBambuPrintStatusUiData.toRemote(): HaBambuPrintStatusData =
  HaBambuPrintStatusData(
    entityId = null,
    printerName = printerName,
    stageEntityId = null,
    stage = stage,
    progressEntityId = null,
    progressLabel = progressLabel,
    progressFraction = progressFraction,
    accent = accent,
    layerEntityId = null,
    layerLine = layerLine,
    remainingEntityId = null,
    remainingLine = remainingLine,
    nozzleEntityId = null,
    nozzleLine = nozzleLine,
    bedEntityId = null,
    bedLine = bedLine,
    phase = phase,
  )

internal fun HaBambuControlButtonUiData.toRemote(): HaBambuControlButton =
  HaBambuControlButton(label = label, icon = icon, accent = accent, tapAction = tapAction)

internal fun HaBambuPrintControlUiData.toRemote(): HaBambuPrintControlData =
  HaBambuPrintControlData(
    printerName = printerName,
    pause = pause?.toRemote(),
    resume = resume?.toRemote(),
    stop = stop?.toRemote(),
  )

internal fun HaBambuSpoolSlotUiData.toRemote(): HaBambuSpoolSlot =
  HaBambuSpoolSlot(
    entityId = null,
    slotLabel = slotLabel,
    material = LiveValues.state(null, material),
    color = color,
    remainPercent = remainPercent,
    active = active,
    materialText = material,
  )

internal fun HaBambuAmsUiData.toRemote(): HaBambuAmsData =
  HaBambuAmsData(title = title, slots = slots.map { it.toRemote() })

internal fun HaBambuSpoolDetailUiData.toRemote(): HaBambuSpoolDetail =
  HaBambuSpoolDetail(slot = slot.toRemote())
