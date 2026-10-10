@file:OptIn(androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi::class)

package ee.schimke.adaptivepilot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.*
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.net.URI
import kotlinx.coroutines.launch

/** Stock-M3 design proposals. No UID, server session, token or live card is loaded here. */
@Composable
fun KeyScreen(
  screen: String,
  dark: Boolean = false,
  initialDetail: Boolean = false,
  initialSelectedIndex: Int = 0,
  unavailable: Boolean = false,
  emptySelection: Boolean = false,
  onAction: (String, String) -> Unit = { _, _ -> },
) {
  val spec = screenSpecs.getValue(screen)
  require(initialSelectedIndex in spec.panels.indices)
  var selectedDashboards by rememberSaveable {
    mutableStateOf(if (emptySelection) emptyList<String>() else listOf("overview", "energy"))
  }
  var switchStates by
    rememberSaveable(screen) { mutableStateOf(listOf(screen == "dashboard-view", false)) }
  val window = currentWindowAdaptiveInfoV2()
  MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
    Scaffold { padding ->
      val navigator =
        rememberListDetailPaneScaffoldNavigator<Int>(
          scaffoldDirective = calculatePaneScaffoldDirective(window),
          initialDestinationHistory =
            listOf(
              ThreePaneScaffoldDestinationItem(
                if (initialDetail) ListDetailPaneScaffoldRole.Detail
                else ListDetailPaneScaffoldRole.List,
                initialSelectedIndex,
              )
            ),
        )
      val selected = navigator.currentDestination?.contentKey ?: 0
      val scope = rememberCoroutineScope()
      ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        modifier = Modifier.fillMaxSize().padding(padding),
        listPane = {
          AnimatedPane {
            ScreenColumn("$screen-list") {
              Text("Home Assistant", style = MaterialTheme.typography.labelLarge)
              Text(spec.title, style = MaterialTheme.typography.headlineMedium)
              Text(spec.summary, style = MaterialTheme.typography.bodyLarge)
              InfoCard(
                if (unavailable) spec.unavailableTitle else spec.statusTitle,
                if (unavailable) spec.unavailableBody else spec.status,
              )
              spec.panels.forEachIndexed { index, panel ->
                Button(
                  onClick = {
                    scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, index) }
                  },
                  modifier =
                    Modifier.fillMaxWidth().testTag("panel-$index").semantics {
                      this.selected =
                        if (screen == "choose-dashboards") panel.id in selectedDashboards
                        else selected == index
                    },
                ) {
                  val viewing =
                    navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] ==
                      PaneAdaptedValue.Expanded && selected == index
                  Text(
                    if (screen == "choose-dashboards")
                      "${if (viewing) "Viewing · " else ""}${panel.title} · ${if (panel.id in selectedDashboards) "Included" else "Hidden"}"
                    else if (viewing) "Selected · ${panel.title}" else panel.title
                  )
                }
              }
              Text(spec.note, style = MaterialTheme.typography.bodyMedium)
            }
          }
        },
        detailPane = {
          AnimatedPane {
            val panel = spec.panels[selected]
            ScreenColumn("$screen-detail") {
              Text(panel.title, style = MaterialTheme.typography.headlineMedium)
              Text(panel.subtitle, style = MaterialTheme.typography.titleLarge)
              InfoCard(panel.cardTitle, panel.body)
              when (screen) {
                "connection" -> {
                  var url by
                    rememberSaveable(selected) {
                      mutableStateOf(
                        if (selected == 0) "http://homeassistant.local:8123"
                        else "https://home.example.com"
                      )
                    }
                  TextField(
                    url,
                    { url = it },
                    Modifier.fillMaxWidth().testTag("input"),
                    singleLine = true,
                    label = { Text("Base URL") },
                  )
                  if (unavailable)
                    Text(
                      "Could not connect. Check the address and try again.",
                      style = MaterialTheme.typography.bodyLarge,
                    )
                  Button(
                    onClick = { onAction("connect", url.trim()) },
                    enabled = validBaseUrl(url),
                    modifier = Modifier.testTag("action"),
                  ) {
                    Text(if (unavailable) "Try again" else "Connect")
                  }
                  TextButton(onClick = { onAction("demo", "") }) {
                    Text("Try demo mode (no login)")
                  }
                }
                "dashboard-view" -> {
                  if (unavailable)
                    Text(
                      "Offline · Saved values may be out of date. Controls are disabled.",
                      style = MaterialTheme.typography.bodyLarge,
                    )
                  val on = switchStates[selected]
                  val label = if (selected == 0) "Living room lamp" else "Kitchen light"
                  Text(label, style = MaterialTheme.typography.titleMedium)
                  Switch(
                    on,
                    {
                      switchStates = switchStates.mapIndexed { index, value ->
                        if (index == selected) it else value
                      }
                      onAction(
                        if (selected == 0) "light.living_room" else "light.kitchen",
                        it.toString(),
                      )
                    },
                    Modifier.testTag("control").semantics { contentDescription = label },
                    enabled = !unavailable,
                  )
                }
                "choose-dashboards" -> {
                  val include = panel.id in selectedDashboards
                  Text("Show in dashboard picker", style = MaterialTheme.typography.titleMedium)
                  Switch(
                    include,
                    { checked ->
                      selectedDashboards =
                        if (checked) selectedDashboards + panel.id
                        else selectedDashboards - panel.id
                    },
                    Modifier.testTag("control").semantics {
                      contentDescription = "Show in dashboard picker"
                    },
                  )
                  Button(
                    onClick = {
                      onAction("save-selection", selectedDashboards.sorted().joinToString(","))
                    },
                    enabled = selectedDashboards.isNotEmpty(),
                    modifier = Modifier.testTag("action"),
                  ) {
                    Text("Save selection")
                  }
                  if (selectedDashboards.isEmpty())
                    Text(
                      "Select at least one dashboard.",
                      style = MaterialTheme.typography.bodyLarge,
                    )
                }
                "settings" -> {
                  val enabled = switchStates[selected]
                  val label = if (selected == 0) "Demo mode" else "Experimental grid layout"
                  Text(label, style = MaterialTheme.typography.titleMedium)
                  Switch(
                    enabled,
                    {
                      switchStates = switchStates.mapIndexed { index, value ->
                        if (index == selected) it else value
                      }
                      onAction(if (selected == 0) "demo-mode" else "grid-layout", it.toString())
                    },
                    Modifier.testTag("control").semantics { contentDescription = label },
                  )
                }
                "widgets" ->
                  Text(
                    "Long-press a dashboard card to add a home-screen widget. Up to 5 can be pinned.",
                    style = MaterialTheme.typography.bodyLarge,
                  )
                "pinned" ->
                  Button(
                    onClick = { onAction("unpin", panel.id) },
                    modifier = Modifier.testTag("action"),
                  ) {
                    Text("Unpin card")
                  }
                "wear-widgets" -> {
                  Button(
                    onClick = { onAction("assign-slot", "${selected + 1}:light.living_room") },
                    modifier = Modifier.testTag("assign"),
                  ) {
                    Text("Assign living room lamp")
                  }
                  TextButton(
                    onClick = { onAction("clear-slot", "${selected + 1}") },
                    modifier = Modifier.testTag("action"),
                    enabled = selected == 0,
                  ) {
                    Text("Clear slot")
                  }
                }
                "sync-diagnostics" ->
                  TextButton(
                    onClick = { onAction("reset-counters", "") },
                    modifier = Modifier.testTag("action"),
                  ) {
                    Text("Reset counters")
                  }
                "logs" ->
                  TextButton(
                    onClick = { onAction("clear-logs", "") },
                    modifier = Modifier.testTag("action"),
                  ) {
                    Text("Clear logs")
                  }
                "card-history" ->
                  Text(
                    "History is read only. Recorded times and values are fixed sample data.",
                    style = MaterialTheme.typography.bodyLarge,
                  )
              }
              TextButton(
                onClick = {
                  scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.List, selected) }
                },
                modifier = Modifier.testTag("back-to-list"),
              ) {
                Text("Back to ${spec.title.lowercase()}")
              }
            }
          }
        },
      )
    }
  }
}

internal fun validBaseUrl(value: String): Boolean = runCatching {
  val uri = URI(value.trim())
  uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
}
  .getOrDefault(false)

@Composable
private fun ScreenColumn(tag: String, content: @Composable ColumnScope.() -> Unit) {
  Column(
    Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()).testTag(tag),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    content = content,
  )
}

@Composable
private fun InfoCard(title: String, body: String) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Text(body, style = MaterialTheme.typography.bodyLarge)
    }
  }
}

private data class Panel(
  val title: String,
  val subtitle: String,
  val cardTitle: String,
  val body: String,
  val id: String = title,
)

private data class ScreenSpec(
  val title: String,
  val summary: String,
  val statusTitle: String,
  val status: String,
  val note: String,
  val panels: List<Panel>,
  val unavailableTitle: String = "Unavailable",
  val unavailableBody: String = "Try again when a connection is available.",
)

private val screenSpecs =
  mapOf(
    "connection" to
      ScreenSpec(
        "Connect",
        "Connect to your Home Assistant instance.",
        "Nearby instance",
        "Home · homeassistant.local:8123",
        "Sign-in opens in your browser. No password or access token is entered here.",
        listOf(
          Panel(
            "Local instance",
            "On your home network",
            "Home",
            "Use the base address of your Home Assistant server.",
          ),
          Panel(
            "Remote instance",
            "Away from home",
            "Public address",
            "Use your Home Assistant HTTPS address.",
          ),
        ),
        "Connection unavailable",
        "The server could not be reached. Check the address and network.",
      ),
    "dashboard-view" to
      ScreenSpec(
        "Home",
        "Your rooms and frequently used controls.",
        "Connected",
        "Home · Live updates",
        "Demo controls · No devices are changed.",
        listOf(
          Panel(
            "Living room",
            "Comfort and lighting",
            "Room overview",
            "Temperature 21.5 °C\nHumidity 46%\nLamp available",
            "light.living_room",
          ),
          Panel(
            "Kitchen",
            "Lighting and environment",
            "Room overview",
            "Temperature 20.8 °C\nHumidity 42%\nLight available",
            "light.kitchen",
          ),
        ),
        "Offline snapshot",
        "Saved at 12:00 · Values may be out of date.",
      ),
    "choose-dashboards" to
      ScreenSpec(
        "Choose dashboards",
        "Choose which dashboards appear in your picker.",
        "Available dashboards",
        "Overview (built-in) · Energy",
        "The built-in Overview can be selected alongside custom dashboards.",
        listOf(
          Panel(
            "Overview (built-in)",
            "Default Home Assistant dashboard",
            "Visibility",
            "Include the built-in dashboard in your picker.",
            "overview",
          ),
          Panel(
            "Energy",
            "Custom dashboard",
            "Visibility",
            "Include your energy dashboard in the picker.",
            "energy",
          ),
        ),
      ),
    "settings" to
      ScreenSpec(
        "Settings",
        "Connection and display preferences.",
        "Configured server",
        "http://homeassistant.local:8123",
        "Demo preferences · Changes stay in this preview.",
        listOf(
          Panel(
            "Session",
            "Connection and demo data",
            "Demo mode",
            "Explore sample dashboards without a Home Assistant connection.",
          ),
          Panel(
            "Display",
            "Layout preferences",
            "Experimental grid",
            "Enable the experimental dashboard grid layout.",
          ),
        ),
      ),
    "widgets" to
      ScreenSpec(
        "Installed widgets",
        "Add dashboard cards to your phone's home screen.",
        "No widgets yet",
        "Add your first widget from a dashboard card.",
        "Long-press a dashboard card to add it to your home screen.",
        listOf(
          Panel(
            "Add a widget",
            "From a dashboard card",
            "Get started",
            "Open a dashboard and long-press a card.\nChoose Add to Home Screen.",
          ),
          Panel(
            "Widget limits",
            "Home-screen widgets",
            "Up to 5",
            "You can pin up to five cards to your home screen.",
          ),
        ),
      ),
    "pinned" to
      ScreenSpec(
        "Manage pinned",
        "Cards and sections saved for quick access.",
        "2 pinned cards",
        "Living room lamp · Kitchen temperature",
        "Unpin removes a saved shortcut; it does not remove the Home Assistant entity.",
        listOf(
          Panel(
            "Living room lamp",
            "Pinned card",
            "Light card",
            "Saved from Home · Living room\nAvailable for Wear widget assignment",
            "light.living_room",
          ),
          Panel(
            "Kitchen temperature",
            "Pinned card",
            "Sensor card",
            "Saved from Home · Kitchen\nTemperature 20.8 °C",
            "sensor.kitchen_temperature",
          ),
        ),
      ),
    "wear-widgets" to
      ScreenSpec(
        "Wear widgets",
        "Assign pinned cards to widget slots on your watch.",
        "Watch slots",
        "Slot 1 assigned · Slot 2 empty",
        "Empty slots are hidden from the watch's widget picker.",
        listOf(
          Panel(
            "Slot 1",
            "Assigned widget",
            "Living room lamp",
            "Pinned light card\nClear this slot to remove it from your watch.",
          ),
          Panel(
            "Slot 2",
            "Empty widget",
            "No card assigned",
            "Assign a pinned card to make this slot available on your watch.",
          ),
        ),
      ),
    "sync-diagnostics" to
      ScreenSpec(
        "Sync diagnostics",
        "Inspect the phone-to-watch data stream.",
        "Watch connected",
        "Live deltas while the watch lease is active",
        "Demo counters · Sample watch traffic.",
        listOf(
          Panel(
            "Active stream",
            "Live deltas",
            "Sync counters",
            "DataItem writes: 12\nMessageClient sends: 48\nLast message: 2 seconds ago",
          ),
          Panel(
            "Idle stream",
            "Batched data writes",
            "Sync counters",
            "DataItem writes: 16\nMessageClient sends: 48\nNo foreground watch lease",
          ),
        ),
      ),
    "logs" to
      ScreenSpec(
        "Logs",
        "Connection and action diagnostics.",
        "Connection healthy",
        "WebSocket connected · Last update 12:00",
        "Access tokens and credentials are hidden.",
        listOf(
          Panel(
            "Connection events",
            "Today",
            "Recent events",
            "12:00 Connected to Home Assistant\n12:00 Subscribed to state changes\n12:01 Received state update",
          ),
          Panel(
            "Action events",
            "Today",
            "Recent events",
            "12:02 light.turn_on requested\n12:02 State update received\n12:03 Updated card state",
          ),
        ),
      ),
    "card-history" to
      ScreenSpec(
        "Card history",
        "Recorded state changes for the selected entity.",
        "Kitchen temperature",
        "sensor.kitchen_temperature · °C",
        "Select a time range to review recorded values.",
        listOf(
          Panel(
            "Last hour",
            "Kitchen temperature",
            "Recorded values",
            "11:00   20.2 °C\n11:20   20.5 °C\n11:40   20.8 °C\n12:00   20.8 °C",
          ),
          Panel(
            "Last 24 hours",
            "Kitchen temperature",
            "Summary",
            "Minimum 18.9 °C\nMaximum 22.1 °C\nLatest 20.8 °C",
          ),
        ),
      ),
  )
