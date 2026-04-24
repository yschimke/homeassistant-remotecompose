@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.tooling.preview.RemotePreview
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.ha.rc.CardWidthClass
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.cardHeightDp
import ee.schimke.ha.rc.cardWidthClass
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.haThemeFor
import ee.schimke.terrazzo.ui.LocalIsDarkTheme
import ee.schimke.terrazzo.ui.LocalThemeStyle
import ee.schimke.terrazzo.ui.rememberLayoutConfig
import kotlinx.coroutines.delay

/**
 * Renders every top-level card in a dashboard as an independent `.rc`
 * document hosted in its own `RemotePreview`. This matches the
 * widget-per-card model — a dashboard here is a stack of players, not
 * one giant document.
 *
 * Layout is single-column on every form factor; the only thing that
 * scales with width is how many [CardWidthClass.Compact] cards (tile,
 * button, entity) we pack into a row. On phones a "lights cluster" of
 * four buttons fits 2-up; on tablets / unfolded foldables 4-up. Full
 * cards (entities, glance, markdown, shutter) stay one-per-row at any
 * width — they'd be unreadable side-by-side on a wall display anyway.
 *
 * The dashboard column is centred and capped to ~840 dp on Expanded
 * widths so an `entities` list doesn't render 1280 dp wide and become
 * a horizontal eyeline-skipping disaster.
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
        is DashboardState.Ready -> DashboardList(s.dashboard, s.snapshot, onCardLongPress, contentPadding)
    }
}

private sealed interface DashboardState {
    data object Loading : DashboardState
    data class Error(val message: String) : DashboardState
    data class Ready(val dashboard: Dashboard, val snapshot: HaSnapshot) : DashboardState
}

@Composable
private fun DashboardList(
    dashboard: Dashboard,
    snapshot: HaSnapshot,
    onCardLongPress: (CardConfig) -> Unit,
    contentPadding: PaddingValues,
) {
    val registry = remember { defaultRegistry() }
    val layout = rememberLayoutConfig()
    val rows = remember(dashboard, snapshot, layout.compactCardsPerRow) {
        packRows(flattenCards(dashboard), snapshot) { card ->
            registry.cardWidthClass(card, snapshot)
        }.let { groups -> chunkCompactGroups(groups, layout.compactCardsPerRow) }
    }

    // The inner column carries the contentPadding so the LazyColumn
    // itself fills the window edge-to-edge (background paints behind
    // status bar / nav bar). centring + maxContentWidth comes via
    // `widthIn` on the LazyColumn modifier, applied inside an outer
    // Box that consumes the leftover horizontal space.
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .columnMaxWidth(layout.maxContentWidth)
                .padding(horizontal = layout.horizontalGutter),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                CardRow(row, snapshot, registry, onCardLongPress)
            }
        }
    }
}

private fun Modifier.columnMaxWidth(maxContentWidth: Dp): Modifier =
    if (maxContentWidth == Dp.Unspecified) this else widthIn(max = maxContentWidth)

/**
 * One LazyColumn item — either a single full-width card or a row of
 * 2..N compact cards sharing the row equally. Height pins to the
 * tallest card in the row so [RemotePreview] (which sizes to its
 * container) gets bounded constraints.
 */
@Composable
private fun CardRow(
    row: DashboardRow,
    snapshot: HaSnapshot,
    registry: ee.schimke.ha.rc.CardRegistry,
    onLongPress: (CardConfig) -> Unit,
) {
    val rowHeightDp = remember(row, snapshot) {
        row.cards.maxOf { registry.cardHeightDp(it, snapshot) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeightDp.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        row.cards.forEach { card ->
            CardSlot(
                card = card,
                snapshot = snapshot,
                registry = registry,
                onLongPress = onLongPress,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

/**
 * Hosts one card's own `.rc` document. Sized by the parent Row (via
 * `weight(1f)`) so packing logic decides the slot width, not the
 * RemoteCompose document.
 */
@Composable
private fun CardSlot(
    card: CardConfig,
    snapshot: HaSnapshot,
    registry: ee.schimke.ha.rc.CardRegistry,
    onLongPress: (CardConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalThemeStyle.current
    val dark = LocalIsDarkTheme.current
    // Re-capture the `.rc` document when the user switches theme — the
    // document's colours are baked at capture, so we key the
    // `RemotePreview` on the style+dark pair.
    val haTheme = remember(style, dark) { haThemeFor(style, dark) }
    Box(
        modifier = modifier
            .combinedClickLongPress(onLongPress = { onLongPress(card) }),
    ) {
        RemotePreview(profile = androidXExperimental) {
            ProvideCardRegistry(registry) {
                ProvideHaTheme(haTheme) {
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

/**
 * One emitted dashboard row. Either a single full-width card or a
 * cluster of compact cards that have been deemed "shareable".
 */
private data class DashboardRow(val key: String, val cards: List<CardConfig>)

/**
 * Group consecutive cards by [CardWidthClass]. The dashboard's authored
 * order is preserved — a Full card between two Compact runs splits
 * them into two clusters, never reorders.
 */
private fun packRows(
    cards: List<CardConfig>,
    snapshot: HaSnapshot,
    widthClassOf: (CardConfig) -> CardWidthClass,
): List<List<CardConfig>> {
    val groups = mutableListOf<List<CardConfig>>()
    val pending = mutableListOf<CardConfig>()
    var pendingClass: CardWidthClass? = null
    for (card in cards) {
        val cls = widthClassOf(card)
        if (cls == CardWidthClass.Full) {
            if (pending.isNotEmpty()) {
                groups += pending.toList(); pending.clear(); pendingClass = null
            }
            groups += listOf(card)
        } else {
            if (pendingClass != null && pendingClass != cls) {
                groups += pending.toList(); pending.clear()
            }
            pending += card
            pendingClass = cls
        }
    }
    if (pending.isNotEmpty()) groups += pending.toList()
    return groups
}

/**
 * Chunk Compact groups into rows of [perRow] siblings while keeping
 * Full groups as standalone single-card rows. Stable keys are
 * synthesised from the position so LazyColumn can recycle correctly
 * across recompositions.
 */
private fun chunkCompactGroups(
    groups: List<List<CardConfig>>,
    perRow: Int,
): List<DashboardRow> {
    var index = 0
    return buildList {
        groups.forEach { group ->
            if (group.size <= 1) {
                add(DashboardRow(key = "row-${index++}", cards = group))
            } else {
                group.chunked(perRow).forEach { chunk ->
                    add(DashboardRow(key = "row-${index++}", cards = chunk))
                }
            }
        }
    }
}
