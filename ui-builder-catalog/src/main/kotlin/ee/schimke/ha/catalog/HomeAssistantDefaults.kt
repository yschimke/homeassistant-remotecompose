package ee.schimke.ha.catalog

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
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.ui.HaAlarmActionUiData
import ee.schimke.ha.rc.ui.HaAlarmPanelUiData
import ee.schimke.ha.rc.ui.HaAlarmStatusUiData
import ee.schimke.ha.rc.ui.HaArcDialUiData
import ee.schimke.ha.rc.ui.HaAreaActionUiData
import ee.schimke.ha.rc.ui.HaAreaCardUiData
import ee.schimke.ha.rc.ui.HaAreaStatUiData
import ee.schimke.ha.rc.ui.HaBambuAmsUiData
import ee.schimke.ha.rc.ui.HaBambuControlButtonUiData
import ee.schimke.ha.rc.ui.HaBambuPrintControlUiData
import ee.schimke.ha.rc.ui.HaBambuPrintStatusUiData
import ee.schimke.ha.rc.ui.HaBambuSpoolDetailUiData
import ee.schimke.ha.rc.ui.HaBambuSpoolSlotUiData
import ee.schimke.ha.rc.ui.HaButtonUiData
import ee.schimke.ha.rc.ui.HaCalendarEventUiData
import ee.schimke.ha.rc.ui.HaCalendarUiData
import ee.schimke.ha.rc.ui.HaClockUiData
import ee.schimke.ha.rc.ui.HaEntitiesUiData
import ee.schimke.ha.rc.ui.HaEntityRowUiData
import ee.schimke.ha.rc.ui.HaGaugeBandsUiData
import ee.schimke.ha.rc.ui.HaGaugeUiData
import ee.schimke.ha.rc.ui.HaGlanceCellUiData
import ee.schimke.ha.rc.ui.HaGlanceUiData
import ee.schimke.ha.rc.ui.HaHeadingUiData
import ee.schimke.ha.rc.ui.HaHistoryGraphRowUiData
import ee.schimke.ha.rc.ui.HaHistoryGraphUiData
import ee.schimke.ha.rc.ui.HaLogbookEntryUiData
import ee.schimke.ha.rc.ui.HaLogbookUiData
import ee.schimke.ha.rc.ui.HaMarkdownUiData
import ee.schimke.ha.rc.ui.HaMediaControlUiData
import ee.schimke.ha.rc.ui.HaModeChipUiData
import ee.schimke.ha.rc.ui.HaPictureCardUiData
import ee.schimke.ha.rc.ui.HaPictureElementPositionUiData
import ee.schimke.ha.rc.ui.HaPictureElementUiData
import ee.schimke.ha.rc.ui.HaPictureElementsUiData
import ee.schimke.ha.rc.ui.HaPictureEntityUiData
import ee.schimke.ha.rc.ui.HaPictureGlanceCellUiData
import ee.schimke.ha.rc.ui.HaPictureGlanceUiData
import ee.schimke.ha.rc.ui.HaSensorCardUiData
import ee.schimke.ha.rc.ui.HaStatisticCardUiData
import ee.schimke.ha.rc.ui.HaStatisticsChartType
import ee.schimke.ha.rc.ui.HaStatisticsGraphUiData
import ee.schimke.ha.rc.ui.HaTileUiData
import ee.schimke.ha.rc.ui.HaTodoItemUiData
import ee.schimke.ha.rc.ui.HaTodoListUiData
import ee.schimke.ha.rc.ui.HaToggleAccentUi
import ee.schimke.ha.rc.ui.HaUnsupportedUiData
import ee.schimke.ha.rc.ui.HaWeatherDayUiData
import ee.schimke.ha.rc.ui.HaWeatherExtraUiData
import ee.schimke.ha.rc.ui.HaWeatherForecastUiData

/**
 * Insertion defaults for every adapter: the same plain data the `previews` module's Tier-2 previews
 * draw (`Tier2CardPreviews.kt`), so a card dropped on the canvas looks like its preview. Each one
 * round trips through its codec, which the adapter declaration checks.
 */
object HomeAssistantDefaults {
  val Amber = Color(0xFFFFC107)
  val Orange = Color(0xFFFF9800)
  val Blue = Color(0xFF2196F3)
  val Green = Color(0xFF4CAF50)
  val Red = Color(0xFFF44336)
  val Grey = Color(0xFF8F8F8F)

  private val temperatures = listOf(14.2f, 13.8f, 13.1f, 13.5f, 15.0f, 17.4f, 19.2f, 20.1f, 18.6f)

  /** A toggleable light, on. */
  val lightAccent = HaToggleAccentUi(activeAccent = Amber, inactiveAccent = Grey, isOn = true)

  val heading = HaHeadingUiData(title = "Living room")

  val tile =
    HaTileUiData(
      name = "Kitchen",
      state = "On",
      icon = Icons.Filled.Lightbulb,
      accent = lightAccent,
      tapAction = HaAction.Toggle("light.kitchen"),
    )

  val button =
    HaButtonUiData(
      name = "Kitchen",
      icon = Icons.Filled.Lightbulb,
      accent = lightAccent,
      tapAction = HaAction.Toggle("light.kitchen"),
    )

  val entityRow =
    HaEntityRowUiData(
      name = "Kitchen",
      state = "On",
      icon = Icons.Filled.Lightbulb,
      accent = lightAccent,
      tapAction = HaAction.MoreInfo("light.kitchen"),
    )

  val entities =
    HaEntitiesUiData(
      title = "Living room",
      rows =
        listOf(
          entityRow,
          HaEntityRowUiData(
            name = "Hallway",
            state = "Off",
            icon = Icons.Filled.Lightbulb,
            accent = lightAccent.copy(isOn = false),
          ),
          HaEntityRowUiData(
            name = "Front door",
            state = "Locked",
            icon = Icons.Filled.Lock,
            accent = HaToggleAccentUi(Green, Grey),
          ),
        ),
    )

  val glance =
    HaGlanceUiData(
      title = "Living room",
      cells =
        listOf(
          HaGlanceCellUiData("Kitchen", "On", Icons.Filled.Lightbulb, lightAccent),
          HaGlanceCellUiData(
            "Door",
            "Closed",
            Icons.Filled.DoorFront,
            HaToggleAccentUi(Blue, Grey),
          ),
          HaGlanceCellUiData(
            "Humidity",
            "46 %",
            Icons.Filled.WaterDrop,
            HaToggleAccentUi(Blue, Grey),
          ),
        ),
    )

  val markdown =
    HaMarkdownUiData(
      title = "Welcome home",
      lines = listOf("## Tonight", "The **heating** is on and the doors are locked."),
    )

  val unsupported = HaUnsupportedUiData(cardType = "custom:mushroom-template-card")

  val gauge =
    HaGaugeUiData(
      name = "Living room humidity",
      valueText = "46 %",
      unit = "%",
      value = 46f,
      min = 0.0,
      max = 100.0,
      severity = HaGaugeBandsUiData(green = 40.0, yellow = 60.0, red = 80.0),
    )

  val arcDial =
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

  private val historyRows =
    listOf(
      HaHistoryGraphRowUiData("Outside", "18.6 °C", Blue, temperatures),
      HaHistoryGraphRowUiData("Upstairs", "21.0 °C", Orange, temperatures.map { it + 3f }),
    )

  val historyGraph = HaHistoryGraphUiData("Temperatures", "Last 24h", historyRows)

  val statisticsGraph =
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
    )

  val statisticCard =
    HaStatisticCardUiData(
      name = "Outside temperature",
      valueLabel = "16.4",
      unit = "°C",
      periodLabel = "Today · mean",
      accent = Blue,
    )

  val sensorCard =
    HaSensorCardUiData(
      name = "Outside",
      valueLabel = "18.6 °C",
      accent = Blue,
      points = temperatures,
      rangeLabel = "Last 24h",
    )

  val logbook =
    HaLogbookUiData(
      title = "Activity",
      entries =
        listOf(
          HaLogbookEntryUiData("Front door", "unlocked", "2 min ago", Icons.Filled.LockOpen),
          HaLogbookEntryUiData("Kitchen", "turned on", "15 min ago", Icons.Filled.Lightbulb),
          HaLogbookEntryUiData("Garage", "closed", "1 h ago", Icons.Filled.Garage),
        ),
    )

  val calendar =
    HaCalendarUiData(
      title = "Family",
      rangeLabel = "Next 7 days",
      events =
        listOf(
          HaCalendarEventUiData("Today 18:00", "Football practice", Green),
          HaCalendarEventUiData("Wed 09:30", "Dentist", Blue),
          HaCalendarEventUiData("Sat", "Birthday party", Orange),
        ),
    )

  val todoList =
    HaTodoListUiData(
      title = "Shopping list",
      activeItems = listOf(HaTodoItemUiData("Milk"), HaTodoItemUiData("Bread")),
      completedItems = listOf(HaTodoItemUiData("Coffee")),
    )

  val weather =
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

  val mediaControl =
    HaMediaControlUiData(
      playerName = "Living room speaker",
      title = "Clair de Lune",
      artist = "Claude Debussy",
      accent = Blue,
      isPlaying = true,
      positionFraction = 0.35f,
      positionLabel = "1:45",
      durationLabel = "5:03",
      playPauseAction =
        HaAction.CallService("media_player", "media_play_pause", "media_player.living_room"),
    )

  val alarmPanel =
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
          HaAlarmActionUiData(
            "Arm away",
            Red,
            HaAction.AlarmIntent("alarm_control_panel.home", "arm_away"),
          ),
          HaAlarmActionUiData(
            "Arm home",
            Orange,
            HaAction.AlarmIntent("alarm_control_panel.home", "arm_home"),
          ),
        ),
      showKeypad = false,
    )

  val area =
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
    )

  /** A frozen label: a ticking clock would differ on every render. */
  val clock = HaClockUiData(title = "Home", staticTimeLabel = "10:08", secondaryLabel = "Tue 5 May")

  val picture =
    HaPictureCardUiData(
      name = "Garden",
      captionUrl = "/local/garden.jpg",
      placeholderIcon = Icons.Filled.Image,
      tapAction = HaAction.Navigate("/lovelace/garden"),
    )

  val pictureGlance =
    HaPictureGlanceUiData(
      title = "Living room",
      captionUrl = "/local/living.jpg",
      placeholderIcon = Icons.Filled.Home,
      cells =
        listOf(
          HaPictureGlanceCellUiData(Icons.Filled.Lightbulb, Amber, true, "Lamp"),
          HaPictureGlanceCellUiData(Icons.Filled.DoorFront, Grey, false, "Door"),
        ),
    )

  val pictureElements =
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
    )

  val pictureEntity =
    HaPictureEntityUiData(
      name = "Front door camera",
      state = "Idle",
      icon = Icons.Filled.Videocam,
      accent = HaToggleAccentUi(activeAccent = Blue, inactiveAccent = Grey),
    )

  private val amsSlots =
    listOf(
      HaBambuSpoolSlotUiData("A1", "PLA", Color(0xFFE53935), 80, active = true),
      HaBambuSpoolSlotUiData("A2", "PETG", Color(0xFF1E88E5), 45, active = false),
      HaBambuSpoolSlotUiData("A3", "PLA", Color(0xFFFFFFFF), null, active = false),
      HaBambuSpoolSlotUiData("A4", "TPU", Color(0xFF212121), 12, active = false),
    )

  val bambuPrintStatus =
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
    )

  val bambuPrintControl =
    HaBambuPrintControlUiData(
      printerName = "X1 Carbon",
      pause = HaBambuControlButtonUiData("Pause", Icons.Filled.Pause, Amber),
      resume = HaBambuControlButtonUiData("Resume", Icons.Filled.PlayArrow, Green),
      stop = HaBambuControlButtonUiData("Stop", Icons.Filled.Stop, Red),
    )

  val bambuAms = HaBambuAmsUiData("AMS", amsSlots)

  val bambuSpool = HaBambuSpoolDetailUiData(amsSlots.first())
}
