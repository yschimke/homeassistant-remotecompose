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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.DashboardSummary
import ee.schimke.terrazzo.core.session.HaSession

@Composable
fun DashboardPickerScreen(
    session: HaSession,
    onDashboardPicked: (urlPath: String?) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var dashboards by remember { mutableStateOf<List<DashboardSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session) {
        runCatching {
            session.connect()
            session.listDashboards()
        }.onSuccess {
            // HA returns an empty list if there are no *extra* dashboards; the
            // default dashboard always lives at url_path = null.
            dashboards = it.ifEmpty { listOf(DashboardSummary(urlPath = null, title = "Home")) }
        }.onFailure { error = it.message ?: it::class.simpleName }
    }

    when {
        error != null -> Column(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp)) {
            Text("Couldn't load dashboards", style = MaterialTheme.typography.titleMedium)
            Text(error!!)
        }
        dashboards == null -> Column(
            Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator()
            Text("Fetching dashboards…")
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Dashboards",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            items(dashboards!!) { d ->
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
