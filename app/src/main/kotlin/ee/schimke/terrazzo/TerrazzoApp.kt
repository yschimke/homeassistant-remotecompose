package ee.schimke.terrazzo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.auth.rememberLoginController
import ee.schimke.terrazzo.dashboard.DashboardPickerScreen
import ee.schimke.terrazzo.dashboard.DashboardViewScreen
import ee.schimke.terrazzo.dashboard.HaSession
import ee.schimke.terrazzo.discovery.DiscoveryScreen
import ee.schimke.terrazzo.widget.WidgetInstallSheet
import ee.schimke.terrazzo.widget.WidgetsScreen

/**
 * Root composable. Two phases:
 *
 * 1. **Unauthenticated**: discovery + login flow (one screen, not in
 *    the nav suite). Completing login promotes to phase 2.
 * 2. **Authenticated**: `NavigationSuiteScaffold` with three top-level
 *    destinations — Dashboards, Widgets, Settings. The suite picks
 *    bottom bar / nav rail / nav drawer based on window size class
 *    (phone / foldable-closed / unfolded / tablet). Within
 *    Dashboards, a lightweight back-stack walks picker → view.
 */
@Composable
fun TerrazzoApp() {
    // NOTE: not saveable — an HaSession owns a live WebSocket. Process
    // restart re-walks the discovery / login flow. The refresh token in
    // TokenVault means we can auto-sign-in silently once we wire that up.
    var session by remember { mutableStateOf<HaSession?>(null) }
    var lastError by remember { mutableStateOf<Throwable?>(null) }

    val login = rememberLoginController(
        onReady = { baseUrl, accessToken -> session = HaSession(baseUrl, accessToken) },
        onError = { lastError = it },
    )

    when (val s = session) {
        null -> UnauthenticatedScreen(
            onInstancePicked = { login.start(it) },
            error = lastError,
        )
        else -> AuthenticatedScaffold(s)
    }
}

@Composable
private fun UnauthenticatedScreen(
    onInstancePicked: (String) -> Unit,
    error: Throwable?,
) {
    DiscoveryScreen(onInstancePicked = onInstancePicked)
    error?.let { err ->
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Login failed", style = MaterialTheme.typography.titleMedium)
            Text(err.message ?: err::class.simpleName.orEmpty())
        }
    }
}

private enum class Destination(val label: String) {
    Dashboards("Dashboards"),
    Widgets("Widgets"),
    Settings("Settings"),
}

@Composable
private fun AuthenticatedScaffold(session: HaSession) {
    var current by rememberSaveable { mutableStateOf(Destination.Dashboards) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            item(
                selected = current == Destination.Dashboards,
                onClick = { current = Destination.Dashboards },
                icon = { Icon(Icons.Filled.Dashboard, contentDescription = null) },
                label = { Text("Dashboards") },
            )
            item(
                selected = current == Destination.Widgets,
                onClick = { current = Destination.Widgets },
                icon = { Icon(Icons.Filled.Widgets, contentDescription = null) },
                label = { Text("Widgets") },
            )
            item(
                selected = current == Destination.Settings,
                onClick = { current = Destination.Settings },
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                label = { Text("Settings") },
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            when (current) {
                Destination.Dashboards -> DashboardsTab(session)
                Destination.Widgets -> WidgetsScreen()
                Destination.Settings -> SettingsScreen(session)
            }
        }
    }
}

/**
 * Two-step stack inside the Dashboards tab: picker → view. Kept local
 * here rather than wiring in Nav3's NavDisplay for this shallow flow —
 * when the app gets deeper navigation (per-card detail, widget
 * configure) we promote to a real `NavDisplay`.
 */
@Composable
private fun DashboardsTab(session: HaSession) {
    var opened by rememberSaveable { mutableStateOf<String?>(DASHBOARD_UNSET) }
    var installPending by remember { mutableStateOf<Pair<CardConfig, HaSnapshot>?>(null) }
    val openedValue = opened

    if (openedValue == DASHBOARD_UNSET) {
        DashboardPickerScreen(
            session = session,
            onDashboardPicked = { urlPath -> opened = urlPath },
        )
    } else {
        DashboardViewScreen(
            session = session,
            urlPath = openedValue,
            onCardLongPress = { card ->
                // Reuse an empty snapshot for the preview frame; the
                // installed widget will refresh from HA on first tick.
                installPending = card to HaSnapshot()
            },
        )
    }

    installPending?.let { (card, snapshot) ->
        WidgetInstallSheet(
            baseUrl = session.baseUrl,
            card = card,
            snapshot = snapshot,
            onDismiss = { installPending = null },
        )
    }
}

/** Sentinel for "no dashboard opened yet". null is a valid urlPath (the default dashboard). */
private const val DASHBOARD_UNSET = "__none__"

@Composable
private fun SettingsScreen(session: HaSession) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Connected to", style = MaterialTheme.typography.labelMedium)
        Text(session.baseUrl, style = MaterialTheme.typography.bodyMedium)
    }
}
