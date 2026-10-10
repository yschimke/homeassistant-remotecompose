@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ee.schimke.terrazzo.shared

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.client.HaClient
import ee.schimke.ha.client.HaConfig
import ee.schimke.ha.client.stateSnapshots
import ee.schimke.ha.model.*
import ee.schimke.ha.rc.formatState
import ee.schimke.terrazzo.dashboard.DashboardListState
import ee.schimke.terrazzo.dashboard.DashboardPickerScreen
import ee.schimke.terrazzo.dashboard.DashboardSwitcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private data object DashboardsDestination : NavKey

private data object SettingsDestination : NavKey

private data class DashboardDestination(val path: String?) : NavKey

private class AppConnection(val config: HaConfig) {
  val client = HaClient(config)

  suspend fun close() = client.close()
}

/** The same dashboard-first application runs on Android, desktop and Wasm. */
@Composable
fun TerrazzoMultiplatformApp() {
  val dark = isSystemInDarkTheme()
  MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
    var connection by remember { mutableStateOf<AppConnection?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val active = connection
    Surface(Modifier.fillMaxSize()) {
      if (active == null) {
        ConnectionScreen(busy, error) { url, token ->
          scope.launch {
            busy = true
            error = null
            val candidate = AppConnection(HaConfig(url.trimEnd('/'), token))
            try {
              candidate.client.connect()
              connection = candidate
            } catch (e: CancellationException) {
              withContext(NonCancellable) { candidate.close() }
              throw e
            } catch (e: Exception) {
              withContext(NonCancellable) { candidate.close() }
              error = "Could not connect. Check the address, token, and network access."
            } finally {
              busy = false
            }
          }
        }
      } else {
        key(active) {
          LaunchedEffect(active) {
            try {
              kotlinx.coroutines.awaitCancellation()
            } finally {
              withContext(NonCancellable) { active.close() }
            }
          }
          DashboardShell(active, onSignOut = { connection = null })
        }
      }
    }
  }
}

@Composable
private fun ConnectionScreen(
  busy: Boolean,
  error: String?,
  connect: (String, String) -> Unit,
) {
  var url by rememberSaveable { mutableStateOf("") }
  // Credentials intentionally never enter saved state or browser localStorage.
  var token by remember { mutableStateOf("") }
  Scaffold(topBar = { TopAppBar(title = { Text("Terrazzo") }) }) { padding ->
    Column(
      Modifier.padding(padding)
        .padding(24.dp)
        .widthIn(max = 560.dp)
        .fillMaxWidth()
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("Connect to Home Assistant", style = MaterialTheme.typography.headlineMedium)
      OutlinedTextField(
        url,
        { url = it },
        label = { Text("Home Assistant URL") },
        placeholder = { Text("https://home.example.com") },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        token,
        { token = it },
        label = { Text("Long-lived access token") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        "Create a long-lived access token in your Home Assistant profile. Use an HTTPS server address.",
        style = MaterialTheme.typography.bodySmall,
      )
      error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      Button(
        onClick = { connect(url.trim(), token.trim()) },
        enabled = !busy && validServerUrl(url) && token.isNotBlank(),
      ) {
        Text(if (busy) "Connecting…" else "Connect")
      }
    }
  }
}

internal fun validServerUrl(value: String): Boolean = runCatching {
  val trimmed = value.trim()
  if (
    !trimmed.startsWith("https://") ||
      trimmed
        .substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .isBlank()
  )
    return@runCatching false
  val url = io.ktor.http.Url(trimmed)
  url.protocol == io.ktor.http.URLProtocol.HTTPS &&
    url.host.isNotBlank() &&
    url.user == null &&
    url.password == null &&
    url.fragment.isEmpty() &&
    url.encodedQuery.isEmpty() &&
    !value.any(Char::isWhitespace)
}
  .getOrDefault(false)

@Composable
private fun DashboardShell(connection: AppConnection, onSignOut: () -> Unit) {
  var list by remember { mutableStateOf<DashboardListState>(DashboardListState.Loading) }
  val stack = remember { mutableStateListOf<NavKey>(DashboardDestination(null)) }
  var snapshot by remember(connection) { mutableStateOf(HaSnapshot()) }
  var connectionError by remember(connection) { mutableStateOf<String?>(null) }
  // One subscription per connection, surviving navigation between dashboard views.
  LaunchedEffect(connection) {
    while (true) {
      connection.client.state.first { it != HaClient.ConnectionState.Ready }
      try {
        connection.client.connect()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        connectionError = "Connection lost. Retrying…"
        delay(2_000)
      }
    }
  }
  LaunchedEffect(connection) {
    connection.client.state.collectLatest { state ->
      if (state == HaClient.ConnectionState.Ready) {
        try {
          val events = connection.client.subscribeEvents("state_changed")
          stateSnapshots(events) { connection.client.snapshot() }
            .collect {
              snapshot = it
              connectionError = null
            }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          connectionError = "Connection lost. Retrying…"
          connection.client.disconnect()
        }
      } else connectionError = "Connection lost. Retrying…"
    }
  }
  LaunchedEffect(connection) {
    try {
      val fetched = connection.client.listDashboards()
      list =
        DashboardListState.Ready(
          listOf(DashboardSummary(null, "Home")) + fetched.filter { it.urlPath != null }
        )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      list = DashboardListState.Error("Could not load dashboards. Reconnect to retry.")
    }
  }
  fun select(route: NavKey) {
    stack.clear()
    stack.add(route)
  }
  NavigationSuiteScaffold(
    navigationSuiteItems = {
      item(
        selected = stack.lastOrNull() != SettingsDestination,
        onClick = { select(DashboardsDestination) },
        icon = { Icon(Icons.Default.Home, null) },
        label = { Text("Dashboards") },
      )
      item(
        selected = stack.lastOrNull() == SettingsDestination,
        onClick = { select(SettingsDestination) },
        icon = { Icon(Icons.Default.Settings, null) },
        label = { Text("Settings") },
      )
    }
  ) {
    NavDisplay(
      backStack = stack,
      onBack = { if (stack.size > 1) stack.removeLastOrNull() },
      entryProvider =
        entryProvider {
          entry<DashboardsDestination> {
            DashboardPickerScreen(list, onDashboardPicked = { stack.add(DashboardDestination(it)) })
          }
          entry<SettingsDestination> {
            Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
              Column(
                Modifier.padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
              ) {
                Text(connection.config.baseUrl, style = MaterialTheme.typography.titleMedium)
                Text("Access tokens are kept only for this session.")
                Button(onClick = onSignOut) { Text("Sign out") }
              }
            }
          }
          entry<DashboardDestination> { route ->
            Scaffold(
              topBar = {
                TopAppBar(
                  title = {
                    DashboardSwitcher(
                      (list as? DashboardListState.Ready)?.dashboards.orEmpty(),
                      route.path,
                      onSwitch = { select(DashboardDestination(it)) },
                    )
                  }
                )
              }
            ) { padding ->
              LiveDashboard(
                connection,
                route.path,
                snapshot,
                connectionError,
                Modifier.fillMaxSize().padding(padding),
              )
            }
          }
        },
    )
  }
}

@Composable
private fun LiveDashboard(
  connection: AppConnection,
  path: String?,
  snapshot: HaSnapshot,
  connectionError: String?,
  modifier: Modifier,
) {
  var dashboard by remember(connection, path) { mutableStateOf<Dashboard?>(null) }
  var error by remember(connection, path) { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()
  LaunchedEffect(connection, path) {
    connection.client.state.collectLatest { state ->
      if (state == HaClient.ConnectionState.Ready && dashboard == null) {
        try {
          dashboard = connection.client.fetchDashboard(path)
          error = null
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          error = "Could not load this dashboard. Reconnect to retry."
        }
      }
    }
  }
  fun dispatch(payload: String) {
    scope.launch {
      try {
        dispatchCardAction(connection.client, payload)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        error = "The action could not be completed."
      }
    }
  }
  val current = dashboard
  Column(modifier) {
    (error ?: connectionError)?.let {
      Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
    }
    if (current == null) {
      CircularProgressIndicator(Modifier.padding(24.dp))
    } else
      DashboardContent(current, path, snapshot, Modifier.weight(1f)) { _, card ->
        NativeCard(card, snapshot) { entityId ->
          dispatch(
            buildJsonObject {
              put("type", "ee.schimke.ha.rc.components.HaAction.Toggle")
              put("entityId", entityId)
            }
              .toString()
          )
        }
      }
  }
}

/** Common adaptive grid. The caller supplies generation so Android can retain local converters. */
@Composable
fun DashboardContent(
  dashboard: Dashboard,
  path: String?,
  snapshot: HaSnapshot,
  modifier: Modifier = Modifier,
  renderCard: @Composable (CardKey, CardConfig) -> Unit,
) {
  var selectedView by rememberSaveable(path) { mutableIntStateOf(0) }
  val index = selectedView.coerceIn(0, (dashboard.views.size - 1).coerceAtLeast(0))
  val view = dashboard.views.getOrNull(index)
  Column(modifier) {
    if (dashboard.views.size > 1) {
      Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        dashboard.views.forEachIndexed { i, v ->
          FilterChip(
            selected = i == index,
            onClick = { selectedView = i },
            label = { Text(v.title ?: "View ${i + 1}") },
          )
        }
      }
    }
    if (view == null || (view.cards.isEmpty() && view.sections.all { it.cards.isEmpty() })) {
      Text("This dashboard has no cards.", Modifier.padding(24.dp))
    } else {
      LazyVerticalGrid(
        columns = GridCells.Adaptive(300.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        view.cards.forEachIndexed { i, card ->
          val key = CardKey(path, view.path ?: "view-$index", cardIndex = i, type = card.type)
          item(key = key.toCacheKey()) { renderCard(key, card) }
        }
        view.sections.forEachIndexed { sectionIndex, section ->
          section.title?.let { title ->
            item(span = { GridItemSpan(maxLineSpan) }) {
              Text(title, style = MaterialTheme.typography.titleLarge)
            }
          }
          section.cards.forEachIndexed { i, card ->
            val key = CardKey(path, view.path ?: "view-$index", sectionIndex, i, card.type)
            item(key = key.toCacheKey()) { renderCard(key, card) }
          }
        }
      }
    }
  }
}

@Composable
private fun NativeCard(card: CardConfig, snapshot: HaSnapshot, toggle: (String) -> Unit) {
  val children =
    (card.raw["cards"] as? JsonArray)
      ?.mapNotNull { raw ->
        val obj = raw as? JsonObject ?: return@mapNotNull null
        (obj["type"] as? JsonPrimitive)?.contentOrNull?.let { CardConfig(it, obj) }
      }
      .orEmpty()
  if (children.isNotEmpty()) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      children.forEach { child -> NativeCard(child, snapshot, toggle) }
    }
    return
  }
  val entityId = (card.raw["entity"] as? JsonPrimitive)?.contentOrNull
  val entity = snapshot.states[entityId]
  val entities =
    (card.raw["entities"] as? JsonArray)
      ?.mapNotNull {
        when (it) {
          is JsonPrimitive -> it.contentOrNull
          is JsonObject -> (it["entity"] as? JsonPrimitive)?.contentOrNull
          else -> null
        }
      }
      .orEmpty()
  OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        (card.raw["name"] as? JsonPrimitive)?.contentOrNull
          ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull
          ?: entityId
          ?: card.type,
        style = MaterialTheme.typography.titleMedium,
      )
      if (card.type == "markdown") {
        Text((card.raw["content"] as? JsonPrimitive)?.contentOrNull.orEmpty())
      } else if (card.type == "heading") {
        Text(
          (card.raw["heading"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
          style = MaterialTheme.typography.titleLarge,
        )
      } else if (entities.isNotEmpty()) {
        entities.forEach { id ->
          val value = snapshot.states[id]
          ListItem(
            headlineContent = {
              Text(value?.attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull ?: id)
            },
            supportingContent = { Text(formatState(value)) },
            trailingContent = {
              if (id.substringBefore('.') in setOf("light", "switch", "input_boolean", "fan"))
                Switch(
                  checked = value?.state == "on",
                  onCheckedChange = { toggle(id) },
                  enabled = value?.state in setOf("on", "off"),
                )
            },
          )
        }
      } else if (entity != null) {
        Text(formatState(entity), style = MaterialTheme.typography.headlineSmall)
        if (entityId?.substringBefore('.') in setOf("light", "switch", "input_boolean", "fan")) {
          Switch(
            checked = entity.state == "on",
            onCheckedChange = { toggle(requireNotNull(entityId)) },
            enabled = entity.state in setOf("on", "off"),
          )
        }
      } else {
        Text("This card type is not supported on desktop or web yet.")
      }
    }
  }
}

internal suspend fun dispatchCardAction(client: HaClient, payload: String) {
  val obj = Json.parseToJsonElement(payload).jsonObject
  when (obj["type"]?.jsonPrimitive?.content?.substringAfterLast('.')) {
    "Toggle" -> {
      val id = requireNotNull(obj["entityId"]?.jsonPrimitive?.content)
      client.callService(id.substringBefore('.'), "toggle", id)
    }
    "CallService" ->
      client.callService(
        requireNotNull(obj["domain"]?.jsonPrimitive?.content),
        requireNotNull(obj["service"]?.jsonPrimitive?.content),
        obj["entityId"]?.jsonPrimitive?.contentOrNull,
        obj["serviceData"] as? JsonObject ?: JsonObject(emptyMap()),
      )
    "None" -> Unit
    else -> error("This action is not supported on this platform yet.")
  }
}
