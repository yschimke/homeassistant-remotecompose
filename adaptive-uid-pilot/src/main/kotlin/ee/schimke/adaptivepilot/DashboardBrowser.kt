@file:OptIn(androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi::class)

package ee.schimke.adaptivepilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.launch

// Design proposal with fixed English sample data, not a live session or navigation destination.
// The Compose implementation is independent of ui-builder/designs/dashboards.uid.
@Composable
fun DashboardBrowser(
  dark: Boolean = false,
  initialDetail: Boolean = false,
  initialSelectedIndex: Int = 0,
  onOpen: (String) -> Unit = {},
) {
  require(initialSelectedIndex in entries.indices)
  MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
    Scaffold { padding ->
      BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        // This bar-free capture's content box equals its window. Apps with bars or rails should
        // derive the size class from the window rather than copy this content-box calculation.
        val info =
          WindowAdaptiveInfo(WindowSizeClass.compute(maxWidth.value, maxHeight.value), Posture())
        val navigator =
          rememberListDetailPaneScaffoldNavigator<Int>(
            scaffoldDirective = calculatePaneScaffoldDirective(info),
            initialDestinationHistory =
              listOf(
                androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem(
                  if (initialDetail) ListDetailPaneScaffoldRole.Detail
                  else ListDetailPaneScaffoldRole.List,
                  initialSelectedIndex,
                )
              ),
          )
        val selected = navigator.currentDestination?.contentKey ?: 0
        val scope = rememberCoroutineScope()
        val expansion = rememberPaneExpansionState()
        val density = LocalDensity.current
        LaunchedEffect(density) {
          expansion.setFirstPaneWidth(with(density) { 360.dp.roundToPx() })
        }
        ListDetailPaneScaffold(
          directive = navigator.scaffoldDirective,
          value = navigator.scaffoldValue,
          paneExpansionState = expansion,
          listPane = {
            AnimatedPane {
              Column(
                Modifier.fillMaxSize()
                  .padding(24.dp)
                  .verticalScroll(rememberScrollState())
                  .testTag("dashboards-list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Text("Home Assistant", style = MaterialTheme.typography.labelLarge)
                Text("Dashboards", style = MaterialTheme.typography.headlineMedium)
                Text(
                  "Choose a dashboard to explore your home.",
                  style = MaterialTheme.typography.bodyLarge,
                )
                entries.forEachIndexed { index, entry ->
                  Button(
                    onClick = {
                      scope.launch {
                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, index)
                      }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("entry-$index"),
                  ) {
                    Text(entry.title)
                  }
                }
              }
            }
          },
          detailPane = {
            AnimatedPane {
              val entry = entries[selected]
              Column(
                Modifier.fillMaxSize()
                  .padding(24.dp)
                  .verticalScroll(rememberScrollState())
                  .testTag("dashboards-detail"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Text(entry.title, style = MaterialTheme.typography.headlineMedium)
                Text(entry.subtitle, style = MaterialTheme.typography.titleLarge)
                Text(entry.status, style = MaterialTheme.typography.labelLarge)
                Text(entry.body, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { onOpen(entry.id) }, modifier = Modifier.testTag("open-entry")) {
                  Text("Open dashboard")
                }
                Button(
                  onClick = {
                    scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.List, selected) }
                  },
                  modifier = Modifier.testTag("back-to-list"),
                ) {
                  Text("Back to dashboards")
                }
              }
            }
          },
        )
      }
    }
  }
}

private data class Entry(
  val id: String,
  val title: String,
  val subtitle: String,
  val status: String,
  val body: String,
)

private val entries =
  listOf(
    Entry(
      "home",
      "Home",
      "Your everyday controls, together.",
      "3 rooms · 12 entities",
      "Living room · 21.5 °C\nKitchen · 2 lights on\nBedroom · blinds open",
    ),
    Entry(
      "energy",
      "Energy",
      "Follow energy use across your home.",
      "Today · 8.4 kWh",
      "Solar · 3.2 kWh\nGrid · 5.2 kWh\nBattery · 76%",
    ),
    Entry(
      "security",
      "Security",
      "Check entrances before heading out.",
      "3 entrances · all closed",
      "Front door · locked\nGarage · closed\nGarden gate · closed",
    ),
  )
