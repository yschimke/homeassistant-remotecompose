@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ee.schimke.terrazzo.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import ee.schimke.ha.client.HaClient
import ee.schimke.ha.client.HaConfig
import ee.schimke.ha.client.UnsupportedDashboardException
import ee.schimke.ha.client.listRenderableDashboards
import ee.schimke.ha.client.stateSnapshots
import ee.schimke.ha.model.*
import ee.schimke.ha.rc.formatState
import ee.schimke.terrazzo.dashboard.DashboardListState
import ee.schimke.terrazzo.dashboard.DashboardPickerScreen
import ee.schimke.terrazzo.dashboard.DashboardSwitcher
import io.ktor.http.encodeURLParameter
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

private class AppConnection(
  val config: HaConfig,
  accessTokenProvider: (suspend () -> String)? = null,
  val hostedAction: ((String) -> Boolean)? = null,
) {
  val hosted = accessTokenProvider != null
  val pictures = EntityPictureClient(config, accessTokenProvider)
  val client = HaClient(config, accessTokenProvider = accessTokenProvider)

  suspend fun close() {
    pictures.close()
    client.close()
  }
}

/** The shared dashboard application runs on Android, desktop and Wasm. */
@Composable
fun TerrazzoMultiplatformApp(
  hostedConfig: HaConfig? = null,
  hostedAccessToken: (suspend () -> String)? = null,
  initialDashboardPath: String? = null,
  hostedAction: ((String) -> Boolean)? = null,
) {
  val dark = isSystemInDarkTheme()
  MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
    var connection by remember {
      mutableStateOf(hostedConfig?.let { AppConnection(it, hostedAccessToken, hostedAction) })
    }
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
          DashboardShell(active, initialDashboardPath, onSignOut = { connection = null })
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
        .consumeWindowInsets(padding)
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
      ReportFeedbackButton()
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
private fun DashboardShell(
  connection: AppConnection,
  initialDashboardPath: String?,
  onSignOut: () -> Unit,
) {
  var list by remember { mutableStateOf<DashboardListState>(DashboardListState.Loading) }
  val stack = remember {
    mutableStateListOf<NavKey>(
      if (connection.hosted && initialDashboardPath == null) DashboardsDestination
      else DashboardDestination(initialDashboardPath)
    )
  }
  var snapshot by remember(connection) { mutableStateOf(HaSnapshot()) }
  var connectionError by remember(connection) { mutableStateOf<String?>(null) }
  var designServer by remember { mutableStateOf("https://ha.coo.ee") }
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
          stateSnapshots(events) { connection.client.snapshotWithDevices() }
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
    connection.client.state.collectLatest { state ->
      if (state != HaClient.ConnectionState.Ready) return@collectLatest
      try {
        list = DashboardListState.Ready(connection.client.listRenderableDashboards())
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        list = DashboardListState.Error("Could not load dashboards. Will retry after reconnecting.")
      }
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
                Modifier.padding(padding)
                  .consumeWindowInsets(padding)
                  .padding(24.dp)
                  .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
              ) {
                Text(connection.config.baseUrl, style = MaterialTheme.typography.titleMedium)
                Text("Access tokens are kept only for this session.")
                OutlinedTextField(
                  designServer,
                  { designServer = it },
                  label = { Text("Card design server") },
                  singleLine = true,
                )
                ReportFeedbackButton()
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
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                designServer,
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
  designServer: String,
) {
  val uriHandler = LocalUriHandler.current
  var designCard by remember(connection, path) { mutableStateOf<CardConfig?>(null) }
  var dashboard by remember(connection, path) { mutableStateOf<Dashboard?>(null) }
  var error by remember(connection, path) { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()
  // Keyed on the configured server so changing the setting reloads designs from it.
  val designCache =
    remember(connection, path, designServer) {
      DashboardLoadCache(scope) { key: LibraryDesignKey -> loadLibraryDesign(key, designServer) }
    }
  val pictureCache =
    remember(connection, path) {
      DashboardLoadCache(scope, capacity = 12) { key: EntityPictureKey ->
        decodeEntityPicture(connection.pictures.load(key))
      }
    }
  LaunchedEffect(connection, path) {
    connection.client.state.collectLatest { state ->
      if (state == HaClient.ConnectionState.Ready && dashboard == null) {
        try {
          dashboard = connection.client.fetchDashboard(path)
          error = null
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          error =
            if (e is UnsupportedDashboardException) e.message
            else
              "This dashboard is unavailable or is not a Lovelace dashboard. Choose another from Dashboards."
        }
      }
    }
  }
  fun dispatch(payload: String) {
    scope.launch {
      try {
        if (connection.hostedAction?.invoke(payload) != true) {
          dispatchCardAction(connection.client, payload)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        error = "The action could not be completed."
      }
    }
  }
  var cardStyle by rememberSaveable { mutableStateOf("classic") }
  var styleMenu by remember { mutableStateOf(false) }
  var customizing by rememberSaveable { mutableStateOf(false) }
  val dark = isSystemInDarkTheme()
  fun moreInfo(entityId: String) {
    val payload = buildJsonObject {
      put("type", "ee.schimke.ha.rc.components.HaAction.MoreInfo")
      put("entityId", entityId)
    }
      .toString()
    try {
      if (connection.hostedAction?.invoke(payload) != true)
        uriHandler.openUri(
          connection.config.baseUrl.trimEnd('/') +
            "/lovelace?more-info-entity-id=" +
            entityId.encodeURLParameter()
        )
    } catch (_: Exception) {
      error = "Could not open Home Assistant entity details."
    }
  }
  val editing = designCard
  if (editing != null) {
    CardStudioScreen(
      editing,
      snapshot,
      onBack = { designCard = null },
      initialServerUrl = designServer,
    ) {
      NativeCard(editing, snapshot) {}
    }
    return
  }
  val current = dashboard
  Column(modifier) {
    (error ?: connectionError)?.let {
      Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
    }
    if (current == null) {
      if (error == null) CircularProgressIndicator(Modifier.padding(24.dp))
    } else
      Column(Modifier.weight(1f)) {
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 16.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Box {
            TextButton(onClick = { styleMenu = true }) {
              Text("Card style: ${cardStyle.replaceFirstChar { it.uppercaseChar() }}")
            }
            DropdownMenu(styleMenu, { styleMenu = false }) {
              listOf("classic", "braun", "lcd", "console").forEach { style ->
                DropdownMenuItem(
                  text = { Text(style.replaceFirstChar { it.uppercaseChar() }) },
                  onClick = {
                    cardStyle = style
                    styleMenu = false
                  },
                )
              }
            }
          }
          TextButton(onClick = { customizing = !customizing }) {
            Text(if (customizing) "Done customizing" else "Customize")
          }
        }
        DashboardContent(current, path, snapshot, Modifier.weight(1f)) { _, card ->
          Column {
            AuthenticatedCard(
              card,
              snapshot,
              connection.client,
              designCache,
              pictureCache,
              cardStyle,
              dark,
              onMoreInfo = ::moreInfo,
              onToggle = { entityId ->
                dispatch(
                  buildJsonObject {
                    put("type", "ee.schimke.ha.rc.components.HaAction.Toggle")
                    put("entityId", entityId)
                  }
                    .toString()
                )
              },
              onService = { domain, service, data ->
                dispatch(
                  buildJsonObject {
                    put("type", "ee.schimke.ha.rc.components.HaAction.CallService")
                    put("domain", domain)
                    put("service", service)
                    put("serviceData", data)
                  }
                    .toString()
                )
              },
            )
            if (customizing)
              TextButton(onClick = { designCard = card }) { Text("Design or fix this card") }
          }
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
    if (
      view == null ||
        (view.header?.card == null &&
          view.cards.isEmpty() &&
          view.sections.all { it.cards.isEmpty() })
    ) {
      Text("This dashboard has no cards.", Modifier.padding(24.dp))
    } else {
      val groups = remember(view, path, index) { dashboardGroups(view, path, index) }
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = dashboardColumnCount((maxWidth - 32.dp).value, view.maxColumns)
        LazyVerticalStaggeredGrid(
          columns = StaggeredGridCells.Fixed(columns),
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(16.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalItemSpacing = 16.dp,
        ) {
          view.header?.card?.let { header ->
            item(
              key = "view-header",
              span = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan.FullLine,
            ) {
              renderCard(
                CardKey(path, view.path ?: "view-$index", cardIndex = -1, type = header.type),
                header,
              )
            }
          }
          groups.forEach { group ->
            val visibleCards =
              group.cards.filter { visibleDashboardCard(it.second, snapshot) != null }
            if (visibleCards.isEmpty()) return@forEach
            item(key = group.id) {
              Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                group.title?.let {
                  Text(
                    it,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = 4.dp),
                  )
                }
                visibleCards.forEach { (cardKey, card) ->
                  key(cardKey.toCacheKey()) { renderCard(cardKey, card) }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
internal fun NativeCard(
  card: CardConfig,
  snapshot: HaSnapshot,
  readOnly: Boolean = false,
  onMoreInfo: ((String) -> Unit)? = null,
  onService: ((String, String, JsonObject) -> Unit)? = null,
  toggle: (String) -> Unit,
) {
  val children = childCards(card)
  if (children.isNotEmpty() && card.type in setOf("grid", "horizontal-stack", "vertical-stack")) {
    CardCluster(card, snapshot) { child ->
      NativeCard(child, snapshot, readOnly, onMoreInfo, onService, toggle)
    }
    return
  }
  if (card.type == "markdown") {
    DashboardMarkdown(card, snapshot)
    return
  }
  if (card.type == "heading") {
    Text(
      (card.raw["heading"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
      style = MaterialTheme.typography.titleLarge,
    )
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
  val configuredTap =
    ((card.raw["tap_action"] as? JsonObject)?.get("action") as? JsonPrimitive)?.contentOrNull
  val toggleCard =
    entityId != null && configuredTap == "toggle" && card.type in setOf("tile", "button")
  val inlineToggle =
    !toggleCard &&
      entityId?.substringBefore('.') in setOf("light", "switch", "input_boolean", "fan")
  val tapModifier =
    if (toggleCard)
      Modifier.clickable(
        enabled = !readOnly && entity?.state in setOf("on", "off"),
        role = Role.Button,
      ) {
        toggle(requireNotNull(entityId))
      }
    else if (
      entityId != null &&
        !inlineToggle &&
        onMoreInfo != null &&
        configuredTap in setOf(null, "more-info")
    )
      Modifier.clickable(role = Role.Button) { onMoreInfo(entityId) }
    else Modifier
  OutlinedCard(Modifier.fillMaxWidth().entityDetails(entityId, onMoreInfo).then(tapModifier)) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (entityId?.substringBefore('.') == "light") LightStateIcon(entity?.state)
        Text(
          (card.raw["name"] as? JsonPrimitive)?.contentOrNull
            ?: (card.raw["title"] as? JsonPrimitive)?.contentOrNull
            ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull
            ?: entityId
            ?: if (card.type == "entities") "Status"
            else card.type.replace('-', ' ').replaceFirstChar { it.uppercaseChar() },
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
      }
      if (card.type == "markdown") {
        val source = (card.raw["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        Text(
          JinjaTemplate.render(source, snapshot)
            ?: "This template needs Home Assistant’s renderer. Open entity details in Home Assistant."
        )
      } else if (card.type == "heading") {
        Text(
          (card.raw["heading"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
          style = MaterialTheme.typography.titleLarge,
        )
      } else if (entities.isNotEmpty()) {
        entities.forEach { id ->
          val value = snapshot.states[id]
          ListItem(
            modifier = Modifier.entityDetails(id, onMoreInfo),
            leadingContent =
              if (id.substringBefore('.') == "light") ({ LightStateIcon(value?.state) }) else null,
            headlineContent = {
              Text(value?.attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull ?: id)
            },
            supportingContent = {
              Text(if (value == null) "Entity not found" else formatState(value, snapshot))
            },
            trailingContent = {
              if (
                value != null &&
                  onService != null &&
                  id.substringBefore('.') in setOf("select", "input_select", "text", "input_text")
              )
                EntityEditor(value, !readOnly, onService)
              else if (id.substringBefore('.') in setOf("light", "switch", "input_boolean", "fan"))
                Switch(
                  checked = value?.state == "on",
                  onCheckedChange = { toggle(id) },
                  enabled = !readOnly && value?.state in setOf("on", "off"),
                )
            },
          )
        }
      } else if (entity != null) {
        Text(formatState(entity, snapshot), style = MaterialTheme.typography.bodyLarge)
        if (inlineToggle) {
          Switch(
            checked = entity.state == "on",
            onCheckedChange = { toggle(requireNotNull(entityId)) },
            enabled = !readOnly && entity.state in setOf("on", "off"),
          )
        }
      } else {
        Text(
          if (entityId != null) "Entity not found"
          else if (card.type == "conditional")
            "This condition needs Home Assistant’s evaluator. Open the original dashboard to view it."
          else "Open this card in Home Assistant for its full view."
        )
      }
      val action = cardServiceAction(card)
      if (action != null && onService != null)
        Button(
          enabled = !readOnly && entity?.state !in setOf("unknown", "unavailable"),
          onClick = { onService(action.domain, action.service, action.data) },
        ) {
          Text("Run action")
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
