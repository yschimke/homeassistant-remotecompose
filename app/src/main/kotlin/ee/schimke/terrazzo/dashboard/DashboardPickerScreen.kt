package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.DashboardSummary

/**
 * Dashboard picker. **Presentational** — the dashboards list is loaded
 * by the caller (`DashboardsRoot`) so the same data drives both this
 * screen and the top-bar quick-switcher dropdown without re-querying.
 *
 * The picker is the first-launch experience (when no `lastViewedDashboard`
 * pref is set) and the destination of the system-back gesture from a
 * dashboard view. With the new top-bar dropdown most users won't visit
 * this screen often once they've picked a dashboard.
 */
@Composable
fun DashboardPickerScreen(
    state: DashboardListState,
    onDashboardPicked: (urlPath: String?) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    when (state) {
        DashboardListState.Loading -> Column(
            Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator()
            Text("Fetching dashboards…")
        }
        is DashboardListState.Error -> Column(
            Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
        ) {
            Text("Couldn't load dashboards", style = MaterialTheme.typography.titleMedium)
            Text(state.message)
        }
        is DashboardListState.Ready -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.dashboards, key = { it.urlPath ?: "__default__" }) { d ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .clickable { onDashboardPicked(d.urlPath) },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(d.title, style = MaterialTheme.typography.titleMedium)
                        d.urlPath?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
