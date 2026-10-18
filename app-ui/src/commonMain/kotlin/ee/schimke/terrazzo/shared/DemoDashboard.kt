@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.*
import kotlinx.serialization.json.*

/** Public, deterministic fixture. No connection, credentials, persistence or HA writes. */
object DemoDashboard {
  private val json = Json { ignoreUnknownKeys = true }
  val dashboard: Dashboard =
    json.decodeFromString(
      """{
      "title":"Demo home",
      "views":[
        {"title":"Home","path":"home","sections":[
          {"title":"Living spaces","cards":[
            {"type":"tile","entity":"light.living_room","name":"Living room"},
            {"type":"tile","entity":"light.kitchen","name":"Kitchen"},
            {"type":"entities","title":"Comfort","entities":["sensor.temperature","sensor.humidity"]}
          ]},
          {"title":"Around the house","cards":[
            {"type":"tile","entity":"binary_sensor.front_door","name":"Front door"},
            {"type":"entities","title":"Energy","entities":["sensor.solar","sensor.home_power"]}
          ]}
        ]},
        {"title":"Rooms","path":"rooms","cards":[
          {"type":"entities","title":"Living room","entities":["light.living_room","sensor.temperature","sensor.humidity"]},
          {"type":"tile","entity":"light.kitchen","name":"Kitchen"}
        ]}
      ]
    }"""
    )

  val snapshot =
    HaSnapshot(
      states =
        listOf(
            entity("light.living_room", "on", "Living room"),
            entity("light.kitchen", "off", "Kitchen"),
            entity("sensor.temperature", "21.5", "Temperature", "°C"),
            entity("sensor.humidity", "44", "Humidity", "%"),
            entity("binary_sensor.front_door", "off", "Front door", deviceClass = "door"),
            entity("sensor.solar", "2.4", "Solar production", "kW"),
            entity("sensor.home_power", "860", "Home power", "W"),
          )
          .associateBy { it.entityId }
    )

  private fun entity(
    id: String,
    state: String,
    name: String,
    unit: String? = null,
    deviceClass: String? = null,
  ) =
    EntityState(
      id,
      state,
      buildJsonObject {
        put("friendly_name", name)
        unit?.let { put("unit_of_measurement", it) }
        deviceClass?.let { put("device_class", it) }
      },
    )
}

/** Data-independent demo surface; the host supplies its current dashboard and snapshot. */
@Composable
fun DashboardExperience(
  dashboard: Dashboard = DemoDashboard.dashboard,
  snapshot: HaSnapshot = DemoDashboard.snapshot,
  demo: Boolean = true,
  dataStatus: DemoDataStatus = DemoDataStatus.FAKE,
  dataMode: DemoDataMode = DemoDataMode.FAKE,
  onDataModeChange: ((DemoDataMode) -> Unit)? = null,
) {
  var design by rememberSaveable { mutableStateOf("classic") }
  var dark by rememberSaveable { mutableStateOf(false) }
  MaterialTheme(
    colorScheme = if (dark || design == "console") darkColorScheme() else lightColorScheme()
  ) {
    Scaffold(
      topBar = {
        DemoToolbar(
          title = if (demo) "Demo home" else dashboard.title ?: "Home Assistant",
          status =
            if (!demo) "Read only"
            else
              when (dataStatus) {
                DemoDataStatus.LIVE -> "Live · read only"
                DemoDataStatus.FAKE -> "Fake · read only"
                DemoDataStatus.CONNECTING -> "Connecting… · read only"
                DemoDataStatus.UNAVAILABLE -> "Offline · fake data"
              },
          design = design,
          onDesignChange = { design = it },
          dark = dark,
          onDarkChange = { dark = it },
          dataMode = dataMode,
          dataStatus = dataStatus,
          onDataModeChange = onDataModeChange,
        )
      }
    ) { padding ->
      Column(Modifier.fillMaxSize().padding(padding)) {
        DashboardContent(
          dashboard,
          if (demo) "demo" else "ha-panel",
          snapshot,
          Modifier.weight(1f),
        ) { _, card ->
          LibraryCard(card, snapshot, design, dark || design == "console") {
            NativeCard(card, snapshot, readOnly = true) {}
          }
        }
      }
    }
  }
}

/** Keep controls in one app bar even on narrow phones; menus overlay the dashboard. */
@Composable
private fun DemoToolbar(
  title: String,
  status: String,
  design: String,
  onDesignChange: (String) -> Unit,
  dark: Boolean,
  onDarkChange: (Boolean) -> Unit,
  dataMode: DemoDataMode,
  dataStatus: DemoDataStatus,
  onDataModeChange: ((DemoDataMode) -> Unit)?,
) {
  val styles =
    listOf("classic" to "Classic", "braun" to "Braun", "lcd" to "LCD", "console" to "Console")
  var stylesExpanded by remember { mutableStateOf(false) }
  var optionsExpanded by remember { mutableStateOf(false) }
  TopAppBar(
    title = {
      Column {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
          status,
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    },
    actions = {
      Box {
        TextButton(
          onClick = { stylesExpanded = true },
          modifier =
            Modifier.heightIn(min = 48.dp).semantics {
              contentDescription = "Card style: ${styles.first { it.first == design }.second}"
            },
        ) {
          Text(styles.first { it.first == design }.second)
          Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = stylesExpanded, onDismissRequest = { stylesExpanded = false }) {
          styles.forEach { (id, label) ->
            DropdownMenuItem(
              text = { Text(label) },
              modifier = Modifier.semantics { selected = design == id },
              trailingIcon = { if (design == id) Text("✓") },
              onClick = {
                onDesignChange(id)
                stylesExpanded = false
              },
            )
          }
        }
      }
      Box {
        IconButton(onClick = { optionsExpanded = true }, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Default.MoreVert, contentDescription = "Demo options")
        }
        DropdownMenu(expanded = optionsExpanded, onDismissRequest = { optionsExpanded = false }) {
          DropdownMenuItem(
            text = { Text(if (dark) "Use light theme" else "Use dark theme") },
            onClick = {
              onDarkChange(!dark)
              optionsExpanded = false
            },
          )
          if (onDataModeChange != null) {
            HorizontalDivider()
            listOf(DemoDataMode.AUTO to "Live when available", DemoDataMode.FAKE to "Fake data")
              .forEach { (mode, label) ->
                DropdownMenuItem(
                  text = { Text(label) },
                  modifier = Modifier.semantics { selected = dataMode == mode },
                  trailingIcon = { if (dataMode == mode) Text("✓") },
                  onClick = {
                    onDataModeChange(mode)
                    optionsExpanded = false
                  },
                )
              }
            if (
              dataStatus == DemoDataStatus.CONNECTING || dataStatus == DemoDataStatus.UNAVAILABLE
            ) {
              Text(
                if (dataStatus == DemoDataStatus.CONNECTING) "Connecting to public demo…"
                else "Live demo unavailable · using fake data · retrying",
                Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
        }
      }
    },
  )
}
