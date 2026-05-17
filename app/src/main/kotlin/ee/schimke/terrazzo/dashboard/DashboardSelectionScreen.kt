package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.DashboardSummary
import ee.schimke.terrazzo.core.prefs.PreferencesStore

/**
 * Multi-select screen for the dashboards the user wants surfaced in the
 * picker and the top-bar switcher. Shown once during the signin flow
 * (gated by [PreferencesStore.selectedDashboardUrls] being `null`) and
 * reachable from Settings → "Manage dashboards".
 *
 * Lists everything HA returned from `lovelace/dashboards/list` plus a
 * synthetic entry for the built-in default dashboard
 * ([builtInDefaultDashboard]) — that one isn't in HA's response but is
 * always available at `urlPath = null`, and users invariably expect to
 * be able to opt in to it.
 *
 * Selection is encoded for [PreferencesStore.setSelectedDashboardUrls]:
 * the built-in default's `null` urlPath maps to
 * [PreferencesStore.DEFAULT_DASHBOARD_SENTINEL]; other entries store
 * their `urlPath` verbatim.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardSelectionScreen(
    state: DashboardListState,
    initialSelection: Set<String>?,
    onConfirm: (Set<String>) -> Unit,
    onBack: (() -> Unit)? = null,
    title: String = "Choose dashboards",
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        when (state) {
            DashboardListState.Loading -> LoadingBody(padding)
            is DashboardListState.Error -> ErrorBody(state.message, padding)
            is DashboardListState.Ready ->
                ReadyBody(
                    dashboards = state.dashboards,
                    initialSelection = initialSelection,
                    onConfirm = onConfirm,
                    contentPadding = padding,
                )
        }
    }
}

/**
 * Synthetic entry for HA's built-in (unnamed) dashboard. Stitched into
 * the selection list so users can opt the default dashboard in or out
 * the same way they do the named ones.
 */
val builtInDefaultDashboard: DashboardSummary =
    DashboardSummary(urlPath = null, title = "Overview (built-in)")

/**
 * Merge HA's named dashboards with [builtInDefaultDashboard] for the
 * selection screen. The built-in goes first so its semantics are clear
 * even when the list is long; if HA somehow returned an entry with a
 * `null` url_path we leave that in place and don't double up.
 */
fun List<DashboardSummary>.withBuiltInDefault(): List<DashboardSummary> {
    if (any { it.urlPath == null }) return this
    return listOf(builtInDefaultDashboard) + this
}

/**
 * Encode a [DashboardSummary]'s `urlPath` for storage in
 * [PreferencesStore.selectedDashboardUrls]. The built-in default's
 * `null` becomes [PreferencesStore.DEFAULT_DASHBOARD_SENTINEL]; every
 * other dashboard stores its `urlPath` verbatim.
 */
fun DashboardSummary.selectionKey(): String =
    urlPath ?: PreferencesStore.DEFAULT_DASHBOARD_SENTINEL

@Composable
private fun LoadingBody(padding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator()
        Text("Fetching dashboards…")
    }
}

@Composable
private fun ErrorBody(message: String, padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
        Text("Couldn't load dashboards", style = MaterialTheme.typography.titleMedium)
        Text(message)
    }
}

@Composable
private fun ReadyBody(
    dashboards: List<DashboardSummary>,
    initialSelection: Set<String>?,
    onConfirm: (Set<String>) -> Unit,
    contentPadding: PaddingValues,
) {
    val merged = remember(dashboards) { dashboards.withBuiltInDefault() }
    // First-visit default: opt every dashboard in. Users who want a
    // subset uncheck rather than re-checking everything, and the
    // built-in default is always there if their only dashboard list
    // is HA's hidden one.
    val seed = remember(merged, initialSelection) {
        initialSelection ?: merged.mapTo(mutableSetOf()) { it.selectionKey() }
    }
    var selected by remember(merged) { mutableStateOf(seed) }

    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item(key = "__header__") {
                Text(
                    text = "Pick the dashboards you want in this app. " +
                        "You can change this later from Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            if (merged.size > 1) {
                item(key = "__bulk__") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = {
                                selected = merged.mapTo(mutableSetOf()) { it.selectionKey() }
                            },
                        ) { Text("Select all") }
                        TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
                    }
                }
            }
            items(merged, key = { it.selectionKey() }) { d ->
                val key = d.selectionKey()
                val isChecked = key in selected
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .clickable {
                            selected = if (isChecked) selected - key else selected + key
                        },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + key else selected - key
                            },
                        )
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(d.title, style = MaterialTheme.typography.titleMedium)
                            d.urlPath?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            } ?: Text(
                                "Home Assistant's default dashboard",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = { onConfirm(selected) },
                enabled = selected.isNotEmpty(),
            ) { Text("Done") }
        }
    }
}
