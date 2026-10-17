@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ee.schimke.terrazzo.shared

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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

/** Also used by the HA custom panel, whose host supplies the dashboard and live snapshot. */
@Composable
fun DashboardExperience(
  dashboard: Dashboard = DemoDashboard.dashboard,
  snapshot: HaSnapshot = DemoDashboard.snapshot,
  demo: Boolean = true,
) {
  var design by rememberSaveable { mutableStateOf("classic") }
  var dark by rememberSaveable { mutableStateOf(false) }
  MaterialTheme(
    colorScheme = if (dark || design == "console") darkColorScheme() else lightColorScheme()
  ) {
    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text(if (demo) "Demo home" else dashboard.title ?: "Home Assistant") },
          actions = {
            TextButton(onClick = { dark = !dark }) { Text(if (dark) "Light" else "Dark") }
          },
        )
      }
    ) { padding ->
      Column(Modifier.fillMaxSize().padding(padding)) {
        Text(
          if (demo) "Example home · fake data · read only" else "Home Assistant · read only",
          Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          style = MaterialTheme.typography.labelLarge,
        )
        Row(
          Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          listOf("classic" to "Classic", "braun" to "Braun", "lcd" to "LCD", "console" to "Console")
            .forEach { (id, label) ->
              FilterChip(
                selected = design == id,
                onClick = { design = id },
                label = { Text(label) },
              )
            }
        }
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
