package ee.schimke.ha.previews

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaBambuPrintPhase
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.ui.HaAlarmActionUiData
import ee.schimke.ha.rc.ui.HaAlarmPanel
import ee.schimke.ha.rc.ui.HaAlarmPanelUiData
import ee.schimke.ha.rc.ui.HaAlarmStatusUiData
import ee.schimke.ha.rc.ui.HaArcDial
import ee.schimke.ha.rc.ui.HaArcDialStyle
import ee.schimke.ha.rc.ui.HaArcDialUiData
import ee.schimke.ha.rc.ui.HaArea
import ee.schimke.ha.rc.ui.HaAreaActionUiData
import ee.schimke.ha.rc.ui.HaAreaCardUiData
import ee.schimke.ha.rc.ui.HaAreaStatUiData
import ee.schimke.ha.rc.ui.HaBambuAms
import ee.schimke.ha.rc.ui.HaBambuAmsUiData
import ee.schimke.ha.rc.ui.HaBambuControlButtonUiData
import ee.schimke.ha.rc.ui.HaBambuPrintControl
import ee.schimke.ha.rc.ui.HaBambuPrintControlUiData
import ee.schimke.ha.rc.ui.HaBambuPrintStatus
import ee.schimke.ha.rc.ui.HaBambuPrintStatusUiData
import ee.schimke.ha.rc.ui.HaBambuSpool
import ee.schimke.ha.rc.ui.HaBambuSpoolDetailUiData
import ee.schimke.ha.rc.ui.HaBambuSpoolSlotUiData
import ee.schimke.ha.rc.ui.HaCalendar
import ee.schimke.ha.rc.ui.HaCalendarEventUiData
import ee.schimke.ha.rc.ui.HaCalendarUiData
import ee.schimke.ha.rc.ui.HaCardLayout
import ee.schimke.ha.rc.ui.HaClock
import ee.schimke.ha.rc.ui.HaClockUiData
import ee.schimke.ha.rc.ui.HaGauge
import ee.schimke.ha.rc.ui.HaGaugeBandsUiData
import ee.schimke.ha.rc.ui.HaGaugeStyle
import ee.schimke.ha.rc.ui.HaGaugeUiData
import ee.schimke.ha.rc.ui.HaHistoryGraph
import ee.schimke.ha.rc.ui.HaHistoryGraphRowUiData
import ee.schimke.ha.rc.ui.HaHistoryGraphUiData
import ee.schimke.ha.rc.ui.HaLogbook
import ee.schimke.ha.rc.ui.HaLogbookEntryUiData
import ee.schimke.ha.rc.ui.HaLogbookUiData
import ee.schimke.ha.rc.ui.HaMediaControl
import ee.schimke.ha.rc.ui.HaMediaControlUiData
import ee.schimke.ha.rc.ui.HaModeChipUiData
import ee.schimke.ha.rc.ui.HaPicture
import ee.schimke.ha.rc.ui.HaPictureCardUiData
import ee.schimke.ha.rc.ui.HaPictureElementPositionUiData
import ee.schimke.ha.rc.ui.HaPictureElementUiData
import ee.schimke.ha.rc.ui.HaPictureElements
import ee.schimke.ha.rc.ui.HaPictureElementsUiData
import ee.schimke.ha.rc.ui.HaPictureEntity
import ee.schimke.ha.rc.ui.HaPictureEntityUiData
import ee.schimke.ha.rc.ui.HaPictureGlance
import ee.schimke.ha.rc.ui.HaPictureGlanceCellUiData
import ee.schimke.ha.rc.ui.HaPictureGlanceUiData
import ee.schimke.ha.rc.ui.HaSensorCard
import ee.schimke.ha.rc.ui.HaSensorCardUiData
import ee.schimke.ha.rc.ui.HaStatisticCard
import ee.schimke.ha.rc.ui.HaStatisticCardUiData
import ee.schimke.ha.rc.ui.HaStatisticsChartType
import ee.schimke.ha.rc.ui.HaStatisticsGraph
import ee.schimke.ha.rc.ui.HaStatisticsGraphUiData
import ee.schimke.ha.rc.ui.HaTodoItemUiData
import ee.schimke.ha.rc.ui.HaTodoList
import ee.schimke.ha.rc.ui.HaTodoListUiData
import ee.schimke.ha.rc.ui.HaToggleAccentUi
import ee.schimke.ha.rc.ui.HaWeatherDayUiData
import ee.schimke.ha.rc.ui.HaWeatherExtraUiData
import ee.schimke.ha.rc.ui.HaWeatherForecast
import ee.schimke.ha.rc.ui.HaWeatherForecastUiData

/**
 * Tier-2 (`rc-components-ui`) previews: each card drawn through its plain-data `Ha*` wrapper — the
 * public Compose API apps call — rather than through a converter. Canvas sizes match the Tier-1
 * preview of the same card in [CardPreviews], so the two renders line up side by side; content is
 * hand-built plain data shaped like the Tier-1 fixtures.
 */
@Composable
private fun Tier2Host(content: @Composable (Modifier) -> Unit) {
  ProvideHaTheme(HaTheme.Light) { content(Modifier.fillMaxSize()) }
}

private val Amber = Color(0xFFFFC107)
private val Orange = Color(0xFFFF9800)
private val Blue = Color(0xFF2196F3)
private val Green = Color(0xFF4CAF50)
private val Red = Color(0xFFF44336)
private val Grey = Color(0xFF8F8F8F)

private val Temperatures = listOf(14.2f, 13.8f, 13.1f, 13.5f, 15.0f, 17.4f, 19.2f, 20.1f, 18.6f)

// ——— gauge ———

private val gauge =
  HaGaugeUiData(
    name = "Living room humidity",
    valueText = "46 %",
    unit = "%",
    value = 46f,
    min = 0.0,
    max = 100.0,
    severity = HaGaugeBandsUiData(green = 40.0, yellow = 60.0, red = 80.0),
  )

@Preview(name = "tier2 gauge", showBackground = false, widthDp = 187, heightDp = 150)
@Composable
fun Tier2_Gauge() = Tier2Host { HaGauge(gauge, it) }

@Preview(name = "tier2 gauge stacked", showBackground = false, widthDp = 187, heightDp = 150)
@Composable
fun Tier2_GaugeStacked() = Tier2Host { HaGauge(gauge, it, style = HaGaugeStyle.Stacked) }

@Preview(name = "tier2 gauge wide", showBackground = false, widthDp = 381, heightDp = 80)
@Composable
fun Tier2_GaugeWide() = Tier2Host { HaGauge(gauge, it, style = HaGaugeStyle.Wide) }

// ——— arc dial ———

private val thermostat =
  HaArcDialUiData(
    name = "Living room",
    valueFraction = 0.55f,
    targetFraction = 0.6f,
    centerLabel = "21.5 °C",
    supportingLabel = "↑ 22 °C",
    modeChip = HaModeChipUiData.Static("Heat"),
    accent = Orange,
    showSteppers = true,
    centerIcon = Icons.Filled.Thermostat,
  )

@Preview(name = "tier2 thermostat", showBackground = false, widthDp = 240, heightDp = 290)
@Composable
fun Tier2_Thermostat() = Tier2Host { HaArcDial(thermostat, it) }

@Preview(name = "tier2 humidifier wide", showBackground = false, widthDp = 381, heightDp = 120)
@Composable
fun Tier2_HumidifierWide() = Tier2Host {
  HaArcDial(
    HaArcDialUiData(
      name = "Bedroom humidifier",
      valueFraction = 0.42f,
      targetFraction = 0.5f,
      centerLabel = "42 %",
      supportingLabel = "Target 50 %",
      modeChip = HaModeChipUiData.Toggle(initiallyOn = true, onLabel = "On", offLabel = "Off"),
      accent = Blue,
      centerIcon = Icons.Filled.WaterDrop,
    ),
    it,
    style = HaArcDialStyle.Wide,
  )
}

// ——— history / statistics / statistic / sensor ———

private val historyRows =
  listOf(
    HaHistoryGraphRowUiData("Outside", "18.6 °C", Blue, Temperatures),
    HaHistoryGraphRowUiData("Upstairs", "21.0 °C", Orange, Temperatures.map { it + 3f }),
  )

@Preview(name = "tier2 history-graph", showBackground = false, widthDp = 381, heightDp = 180)
@Composable
fun Tier2_HistoryGraph() = Tier2Host {
  HaHistoryGraph(HaHistoryGraphUiData("Temperatures", "Last 24h", historyRows), it)
}

@Preview(name = "tier2 statistics-graph", showBackground = false, widthDp = 381, heightDp = 180)
@Composable
fun Tier2_StatisticsGraph() = Tier2Host {
  HaStatisticsGraph(
    HaStatisticsGraphUiData(
      title = "Energy",
      rangeLabel = "Last 7 days",
      rows =
        listOf(
          HaHistoryGraphRowUiData("Grid", "41 kWh", Blue, listOf(5f, 6f, 4f, 7f, 6f, 8f, 5f)),
          HaHistoryGraphRowUiData("Solar", "23 kWh", Amber, listOf(3f, 4f, 2f, 5f, 4f, 3f, 2f)),
        ),
      chartType = HaStatisticsChartType.Bar,
      stacked = true,
    ),
    it,
  )
}

@Preview(name = "tier2 statistic", showBackground = false, widthDp = 240, heightDp = 110)
@Composable
fun Tier2_Statistic() = Tier2Host {
  HaStatisticCard(
    HaStatisticCardUiData(
      name = "Outside temperature",
      valueLabel = "16.4",
      unit = "°C",
      periodLabel = "Today · mean",
      accent = Blue,
    ),
    it,
  )
}

@Preview(name = "tier2 sensor", showBackground = false, widthDp = 320, heightDp = 130)
@Composable
fun Tier2_Sensor() = Tier2Host {
  HaSensorCard(
    HaSensorCardUiData(
      name = "Outside",
      valueLabel = "18.6 °C",
      accent = Blue,
      points = Temperatures,
      rangeLabel = "Last 24h",
    ),
    it,
  )
}

// ——— logbook / calendar / todo ———

@Preview(name = "tier2 logbook", showBackground = false, widthDp = 381, heightDp = 165)
@Composable
fun Tier2_Logbook() = Tier2Host {
  HaLogbook(
    HaLogbookUiData(
      title = "Activity",
      entries =
        listOf(
          HaLogbookEntryUiData("Front door", "unlocked", "2 min ago", Icons.Filled.LockOpen),
          HaLogbookEntryUiData("Kitchen", "turned on", "15 min ago", Icons.Filled.Lightbulb),
          HaLogbookEntryUiData("Garage", "closed", "1 h ago", Icons.Filled.Garage),
        ),
    ),
    it,
  )
}

@Preview(name = "tier2 calendar", showBackground = false, widthDp = 381, heightDp = 220)
@Composable
fun Tier2_Calendar() = Tier2Host {
  HaCalendar(
    HaCalendarUiData(
      title = "Family",
      rangeLabel = "Next 7 days",
      events =
        listOf(
          HaCalendarEventUiData("Today 18:00", "Football practice", Green),
          HaCalendarEventUiData("Wed 09:30", "Dentist", Blue),
          HaCalendarEventUiData("Sat", "Birthday party", Orange),
        ),
    ),
    it,
  )
}

@Preview(name = "tier2 todo-list", showBackground = false, widthDp = 320, heightDp = 220)
@Composable
fun Tier2_TodoList() = Tier2Host {
  HaTodoList(
    HaTodoListUiData(
      title = "Shopping list",
      activeItems = listOf(HaTodoItemUiData("Milk"), HaTodoItemUiData("Bread")),
      completedItems = listOf(HaTodoItemUiData("Coffee")),
    ),
    it,
  )
}

// ——— weather ———

private val weather =
  HaWeatherForecastUiData(
    name = "Home",
    condition = "Partly cloudy",
    temperature = "16.7 °C",
    supportingLine = "Feels like 15 °C",
    highLow = "18 °C / 11 °C",
    icon = Icons.Filled.WbCloudy,
    days =
      listOf(
        HaWeatherDayUiData("Tue", "18°", "11°", Icons.Filled.WbCloudy),
        HaWeatherDayUiData("Wed", "20°", "12°", Icons.Filled.WbSunny),
        HaWeatherDayUiData("Thu", "17°", "10°", Icons.Filled.Cloud),
        HaWeatherDayUiData("Fri", "15°", "9°", Icons.Filled.WaterDrop),
      ),
    extras =
      listOf(
        HaWeatherExtraUiData(Icons.Filled.WaterDrop, "Humidity", "72 %"),
        HaWeatherExtraUiData(Icons.Filled.Air, "Wind", "14 km/h"),
      ),
  )

@Preview(name = "tier2 weather-forecast", showBackground = false, widthDp = 381, heightDp = 224)
@Composable
fun Tier2_WeatherForecast() = Tier2Host { HaWeatherForecast(weather, it) }

@Preview(
  name = "tier2 weather-forecast wide",
  showBackground = false,
  widthDp = 381,
  heightDp = 100,
)
@Composable
fun Tier2_WeatherForecastWide() = Tier2Host {
  HaWeatherForecast(weather, it, layout = HaCardLayout.Wide)
}

// ——— media control ———

private val media =
  HaMediaControlUiData(
    playerName = "Living room speaker",
    title = "Clair de Lune",
    artist = "Claude Debussy",
    accent = Blue,
    isPlaying = true,
    positionFraction = 0.35f,
    positionLabel = "1:45",
    durationLabel = "5:03",
  )

@Preview(name = "tier2 media-control", showBackground = false, widthDp = 381, heightDp = 168)
@Composable
fun Tier2_MediaControl() = Tier2Host { HaMediaControl(media, it) }

@Preview(name = "tier2 media-control wide", showBackground = false, widthDp = 381, heightDp = 80)
@Composable
fun Tier2_MediaControlWide() = Tier2Host { HaMediaControl(media, it, layout = HaCardLayout.Wide) }

// ——— alarm panel ———

private val alarm =
  HaAlarmPanelUiData(
    title = "Home alarm",
    initialStateInt = 0,
    statuses =
      listOf(
        HaAlarmStatusUiData(0, "Disarmed", Green, Icons.Filled.Shield),
        HaAlarmStatusUiData(1, "Armed away", Red, Icons.Filled.Lock),
      ),
    actions =
      listOf(
        HaAlarmActionUiData("Arm away", Red, HaAction.None),
        HaAlarmActionUiData("Arm home", Orange, HaAction.None),
      ),
    showKeypad = false,
  )

@Preview(name = "tier2 alarm-panel", showBackground = false, widthDp = 381, heightDp = 200)
@Composable
fun Tier2_AlarmPanel() = Tier2Host { HaAlarmPanel(alarm, it) }

@Preview(name = "tier2 alarm-panel wide", showBackground = false, widthDp = 381, heightDp = 100)
@Composable
fun Tier2_AlarmPanelWide() = Tier2Host { HaAlarmPanel(alarm, it, layout = HaCardLayout.Wide) }

// ——— area / clock ———

@Preview(name = "tier2 area", showBackground = false, widthDp = 280, heightDp = 140)
@Composable
fun Tier2_Area() = Tier2Host {
  HaArea(
    HaAreaCardUiData(
      name = "Living room",
      stats =
        listOf(
          HaAreaStatUiData(Icons.Filled.Thermostat, "21.5 °C"),
          HaAreaStatUiData(Icons.Filled.WaterDrop, "46 %"),
        ),
      actions =
        listOf(
          HaAreaActionUiData(Icons.Filled.Lightbulb, Amber, initiallyActive = true),
          HaAreaActionUiData(Icons.Filled.Air, Blue, initiallyActive = false),
        ),
    ),
    it,
  )
}

@Preview(name = "tier2 clock", showBackground = false, widthDp = 200, heightDp = 100)
@Composable
fun Tier2_Clock() = Tier2Host {
  // Frozen label: a ticking clock would make every render differ.
  HaClock(
    HaClockUiData(title = "Home", staticTimeLabel = "10:08", secondaryLabel = "Tue 5 May"),
    it,
  )
}

// ——— picture family ———

@Preview(name = "tier2 picture", showBackground = false, widthDp = 320, heightDp = 140)
@Composable
fun Tier2_Picture() = Tier2Host {
  HaPicture(
    HaPictureCardUiData(
      name = "Garden",
      captionUrl = "/local/garden.jpg",
      placeholderIcon = Icons.Filled.Image,
    ),
    it,
  )
}

@Preview(name = "tier2 picture-glance", showBackground = false, widthDp = 381, heightDp = 168)
@Composable
fun Tier2_PictureGlance() = Tier2Host {
  HaPictureGlance(
    HaPictureGlanceUiData(
      title = "Living room",
      captionUrl = "/local/living.jpg",
      placeholderIcon = Icons.Filled.Home,
      cells =
        listOf(
          HaPictureGlanceCellUiData(Icons.Filled.Lightbulb, Amber, true, "Lamp"),
          HaPictureGlanceCellUiData(Icons.Filled.DoorFront, Grey, false, "Door"),
        ),
    ),
    it,
  )
}

@Preview(name = "tier2 picture-elements", showBackground = false, widthDp = 381, heightDp = 180)
@Composable
fun Tier2_PictureElements() = Tier2Host {
  HaPictureElements(
    HaPictureElementsUiData(
      captionUrl = "/local/floorplan.png",
      placeholderIcon = Icons.Filled.Home,
      elements =
        listOf(
          HaPictureElementUiData.StateIcon(
            icon = Icons.Filled.Lightbulb,
            accent = Amber,
            initiallyActive = true,
            position = HaPictureElementPositionUiData(leftFraction = 0.25f, topFraction = 0.3f),
          ),
          HaPictureElementUiData.StateLabel(
            text = "21.5 °C",
            position = HaPictureElementPositionUiData(leftFraction = 0.7f, topFraction = 0.6f),
          ),
          HaPictureElementUiData.ServiceButton("All off", Red, HaAction.None),
        ),
    ),
    it,
  )
}

@Preview(name = "tier2 picture-entity", showBackground = false, widthDp = 312, heightDp = 160)
@Composable
fun Tier2_PictureEntity() = Tier2Host {
  HaPictureEntity(
    HaPictureEntityUiData(
      name = "Front door camera",
      state = "Idle",
      icon = Icons.Filled.Videocam,
      accent = HaToggleAccentUi(activeAccent = Blue, inactiveAccent = Grey),
    ),
    it,
  )
}

// ——— Bambu Lab ———

private val amsSlots =
  listOf(
    HaBambuSpoolSlotUiData("A1", "PLA", Color(0xFFE53935), 80, active = true),
    HaBambuSpoolSlotUiData("A2", "PETG", Color(0xFF1E88E5), 45, active = false),
    HaBambuSpoolSlotUiData("A3", "PLA", Color(0xFFFFFFFF), null, active = false),
    HaBambuSpoolSlotUiData("A4", "TPU", Color(0xFF212121), 12, active = false),
  )

@Preview(name = "tier2 bambu print-status", showBackground = false, widthDp = 381, heightDp = 186)
@Composable
fun Tier2_BambuPrintStatus() = Tier2Host {
  HaBambuPrintStatus(
    HaBambuPrintStatusUiData(
      printerName = "X1 Carbon",
      stage = "Printing",
      progressLabel = "42 %",
      progressFraction = 0.42f,
      accent = Green,
      layerLine = "Layer 84 / 200",
      remainingLine = "1 h 12 min left",
      nozzleLine = "Nozzle 220 °C → 220 °C",
      bedLine = "Bed 55 °C → 55 °C",
    ),
    it,
  )
}

@Preview(
  name = "tier2 bambu print-status idle",
  showBackground = false,
  widthDp = 381,
  heightDp = 100,
)
@Composable
fun Tier2_BambuPrintStatusIdle() = Tier2Host {
  HaBambuPrintStatus(
    HaBambuPrintStatusUiData(
      printerName = "A1 mini",
      stage = "Idle",
      progressLabel = "0 %",
      progressFraction = 0f,
      accent = Green,
      nozzleLine = "Nozzle 23 °C",
      bedLine = "Bed 22 °C",
      phase = HaBambuPrintPhase.Idle,
    ),
    it,
  )
}

@Preview(name = "tier2 bambu print-control", showBackground = false, widthDp = 381, heightDp = 124)
@Composable
fun Tier2_BambuPrintControl() = Tier2Host {
  HaBambuPrintControl(
    HaBambuPrintControlUiData(
      printerName = "X1 Carbon",
      pause = HaBambuControlButtonUiData("Pause", Icons.Filled.Pause, Amber),
      resume = HaBambuControlButtonUiData("Resume", Icons.Filled.PlayArrow, Green),
      stop = HaBambuControlButtonUiData("Stop", Icons.Filled.Stop, Red),
    ),
    it,
  )
}

@Preview(name = "tier2 bambu ams", showBackground = false, widthDp = 381, heightDp = 130)
@Composable
fun Tier2_BambuAms() = Tier2Host { HaBambuAms(HaBambuAmsUiData("AMS", amsSlots), it) }

/**
 * Four slots with long material names: each ellipsizes within its share, none is pushed out
 * (#1038).
 */
@Preview(
  name = "tier2 bambu ams (long names)",
  showBackground = false,
  widthDp = 381,
  heightDp = 130,
)
@Composable
fun Tier2_BambuAmsLongNames() = Tier2Host {
  HaBambuAms(
    HaBambuAmsUiData(
      "AMS",
      listOf(
        HaBambuSpoolSlotUiData("A1", "Bambu TPU for AMS", Color(0xFFE53935), 80, active = true),
        HaBambuSpoolSlotUiData("A2", "Generic PLA", Color(0xFF1E88E5), 45, active = false),
        HaBambuSpoolSlotUiData("A3", "Bambu PLA Glow", Color(0xFFFFFFFF), 61, active = false),
        HaBambuSpoolSlotUiData("A4", "Bambu PETG HF", Color(0xFF212121), 32, active = false),
      ),
    ),
    it,
  )
}

@Preview(name = "tier2 bambu spool", showBackground = false, widthDp = 381, heightDp = 96)
@Composable
fun Tier2_BambuSpool() = Tier2Host { HaBambuSpool(HaBambuSpoolDetailUiData(amsSlots.first()), it) }
