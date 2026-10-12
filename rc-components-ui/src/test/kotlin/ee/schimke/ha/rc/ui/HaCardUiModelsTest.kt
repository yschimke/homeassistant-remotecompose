@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaModeChip
import ee.schimke.ha.rc.components.HaPictureElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Plain → Remote conversion for the Tier-2 card models in `HaCardUiModels.kt`. */
class HaCardUiModelsTest {

  private val amber = Color(0xFFFFC107)

  @Test
  fun gauge_maps_text_range_and_severity() {
    val remote =
      HaGaugeUiData(
          name = "CPU",
          valueText = "42 %",
          unit = "%",
          value = 42f,
          min = 0.0,
          max = 100.0,
          severity = HaGaugeBandsUiData(green = 0.0, yellow = 60.0, red = 85.0),
        )
        .toRemote()
    assertNull(remote.entityId)
    assertEquals("42 %", remote.valueText.constantValueOrNull)
    assertEquals(100.0, remote.max)
    assertEquals(85.0, remote.severity.red)
  }

  @Test
  fun arcDial_supporting_label_keeps_an_attribute_key_only_when_present() {
    val base =
      HaArcDialUiData(
        name = "Living room",
        valueFraction = 0.5f,
        targetFraction = 0.6f,
        centerLabel = "21 °C",
        accent = amber,
        centerIcon = Icons.Filled.Thermostat,
      )
    assertNull(base.toRemote().supportingLabelAttribute)
    val withSupporting = base.copy(supportingLabel = "↑ 22 °C").toRemote()
    assertEquals("↑ 22 °C", withSupporting.supportingLabel)
    assertEquals("supporting_label", withSupporting.supportingLabelAttribute)
  }

  @Test
  fun modeChip_sealed_variants_map_one_to_one() {
    val static = HaModeChipUiData.Static("Heat").toRemote()
    assertIs<HaModeChip.Static>(static)
    assertEquals("Heat", static.initial)
    assertNull(static.entityId)

    val toggle = HaModeChipUiData.Toggle(initiallyOn = true, onLabel = "On", offLabel = "Off")
    val remote = assertIs<HaModeChip.Toggle>(toggle.toRemote())
    assertEquals(true, remote.initiallyOn)
    assertEquals("Off", remote.offLabel)
  }

  @Test
  fun statisticsGraph_chartType_maps_to_ha_string() {
    val ui =
      HaStatisticsGraphUiData(
        title = "Energy",
        rangeLabel = "Last 7 days",
        rows =
          listOf(
            HaHistoryGraphRowUiData(
              name = "Grid",
              summary = "12 kWh",
              accent = amber,
              points = listOf(1f, 2f),
            )
          ),
        chartType = HaStatisticsChartType.Bar,
        stacked = true,
      )
    val remote = ui.toRemote()
    assertEquals("bar", remote.chartType)
    assertEquals(true, remote.stacked)
    assertEquals("12 kWh", remote.rows.single().summary.constantValueOrNull)
  }

  @Test
  fun pictureElements_sealed_variants_and_positions_map_through() {
    val ui =
      HaPictureElementsUiData(
        captionUrl = null,
        placeholderIcon = Icons.Filled.Lightbulb,
        elements =
          listOf(
            HaPictureElementUiData.StateIcon(
              icon = Icons.Filled.Lightbulb,
              accent = amber,
              initiallyActive = true,
              tapAction = HaAction.Toggle("light.kitchen"),
              position = HaPictureElementPositionUiData(leftFraction = 0.25f, topFraction = 0.5f),
            ),
            HaPictureElementUiData.StateLabel(text = "21 °C"),
            HaPictureElementUiData.ServiceButton(
              label = "All off",
              accent = amber,
              tapAction = HaAction.None,
            ),
          ),
      )
    val (icon, label, button) = ui.toRemote().elements
    val stateIcon = assertIs<HaPictureElement.StateIcon>(icon)
    assertEquals(0.25f, stateIcon.position?.leftFraction)
    assertEquals(HaAction.Toggle("light.kitchen"), stateIcon.tapAction)
    assertEquals("21 °C", assertIs<HaPictureElement.StateLabel>(label).text.constantValueOrNull)
    assertEquals("All off", assertIs<HaPictureElement.ServiceButton>(button).label)
  }

  @Test
  fun alarmPanel_rejects_duplicate_state_keys() {
    val status = HaAlarmStatusUiData(0, "Disarmed", amber, Icons.Filled.Lightbulb)
    assertFailsWith<IllegalArgumentException> {
      HaAlarmPanelUiData(
        title = "Alarm",
        initialStateInt = 0,
        statuses = listOf(status, status),
        actions = emptyList(),
        showKeypad = false,
      )
    }
  }

  @Test
  fun bambuSpool_material_feeds_both_binding_and_text() {
    val remote =
      HaBambuSpoolDetailUiData(
          HaBambuSpoolSlotUiData(
            slotLabel = "A1",
            material = "PLA",
            color = amber,
            remainPercent = 80,
            active = true,
          )
        )
        .toRemote()
    assertEquals("PLA", remote.slot.material.constantValueOrNull)
    assertEquals("PLA", remote.slot.materialText)
    assertNull(remote.slot.entityId)
  }
}
