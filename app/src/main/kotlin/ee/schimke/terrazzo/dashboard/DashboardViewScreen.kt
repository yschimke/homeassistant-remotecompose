@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.tooling.preview.RemotePreview
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.cardHeightDp
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import kotlinx.coroutines.delay

/**
 * Renders every top-level card in a dashboard as an independent `.rc`
 * document hosted in its own `RemotePreview`. This matches the
 * widget-per-card model — a dashboard here is a responsive grid of
 * players, not one giant document.
 *
 * `LazyVerticalGrid(GridCells.Adaptive(minSize = 320.dp))` collapses
 * to 1 column on folded / compact widths and grows to 2–3 on tablets /
 * unfolded, honoring the nav-3 responsive-ui brief.
 */
@Composable
fun DashboardViewScreen(
    session: HaSession,
    urlPath: String?,
    onCardLongPress: (CardConfig) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var state by remember(session, urlPath) { mutableStateOf<DashboardState>(DashboardState.Loading) }

    LaunchedEffect(session, urlPath) {
        state = DashboardState.Loading
        // Poll when the session opts in (demo mode does, at ~2s) so
        // values visibly change; a one-shot fetch otherwise.
        while (true) {
            runCatching { session.loadDashboard(urlPath) }
                .onSuccess { (dashboard, snapshot) ->
                    state = DashboardState.Ready(dashboard, snapshot)
                }
                .onFailure {
                    if (state is DashboardState.Loading) {
                        state = DashboardState.Error(it.message ?: it::class.simpleName ?: "error")
                    }
                }
            val interval = session.refreshIntervalMillis ?: break
            delay(interval)
        }
    }

    when (val s = state) {
        DashboardState.Loading -> Box(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp)) {
            CircularProgressIndicator()
        }
        is DashboardState.Error -> Box(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp)) {
            Text("Failed: ${s.message}", style = MaterialTheme.typography.bodyMedium)
        }
        is DashboardState.Ready -> DashboardGrid(s.dashboard, s.snapshot, onCardLongPress, contentPadding)
    }
}

private sealed interface DashboardState {
    data object Loading : DashboardState
    data class Error(val message: String) : DashboardState
    data class Ready(val dashboard: Dashboard, val snapshot: HaSnapshot) : DashboardState
}

@Composable
private fun DashboardGrid(
    dashboard: Dashboard,
    snapshot: HaSnapshot,
    onCardLongPress: (CardConfig) -> Unit,
    contentPadding: PaddingValues,
) {
    val registry = remember { defaultRegistry() }
    val cards = remember(dashboard) { flattenCards(dashboard) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 320.dp),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
    ) {
        items(cards) { card ->
            CardSlot(card, snapshot, registry, onCardLongPress)
        }
    }
}

/**
 * Hosts one card's own `.rc` document. Height is pinned so
 * `RemoteDocPreview` (which sizes to its container) has bounded
 * constraints. The value comes from the converter itself
 * (`CardRegistry.cardHeightDp`) — app doesn't know per-card sizing.
 */
@Composable
private fun CardSlot(
    card: CardConfig,
    snapshot: HaSnapshot,
    registry: ee.schimke.ha.rc.CardRegistry,
    onLongPress: (CardConfig) -> Unit,
) {
    val heightDp = remember(card, snapshot) { registry.cardHeightDp(card, snapshot) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .height(heightDp.dp)
            .combinedClickLongPress(onLongPress = { onLongPress(card) }),
    ) {
        RemotePreview(profile = androidXExperimental) {
            ProvideCardRegistry(registry) {
                // TODO: honor dashboard theme; for now we render light.
                ProvideHaTheme(HaTheme.Light) {
                    RenderChild(card, snapshot, RemoteModifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun flattenCards(dashboard: Dashboard): List<CardConfig> =
    dashboard.views.flatMap { view ->
        val fromCards = view.cards
        val fromSections = view.sections.flatMap { it.cards }
        fromCards + fromSections
    }
