@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import ee.schimke.terrazzo.ui.LayoutConfig
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
 * Layout follows the dashboard's own structure as faithfully as the
 * window allows:
 *
 *   - **Wide (Expanded width, ≥2 sections)** — sections render
 *     side-by-side as columns, mirroring HA's modern dashboard. Up
 *     to 2 columns at 840 dp / 3 at 1200 dp. Section titles sit at
 *     the top of each column.
 *   - **Single-column (Compact / Medium, or no sections)** — sections
 *     stack vertically with their titles preserved as inline
 *     headings. Cards inside a section pack consecutive
 *     [CardWidthClass.Compact] entries (tile / button / entity) into
 *     a single row so a four-button cluster doesn't take four full
 *     screen heights on a phone.
 *
 * View titles are not rendered: each top-level dashboard has one
 * "view" in the typical case, and the user already saw the view name
 * in the picker. Multi-view dashboards are an HA tab construct; we'd
 * surface them as tabs, not stacked, when we get to that.
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
    val layout = remember(dashboard) { buildDashboardLayout(dashboard) }
    val cfg = rememberLayoutConfig()

    val sectionColumns = (cfg.maxSectionColumns).coerceAtMost(layout.sectionCount).coerceAtLeast(1)
    val sideBySide = sectionColumns >= 2

    // Centre + cap the column on Expanded ONLY when the dashboard has
    // no sections — sections drive their own grid width and shouldn't
    // be capped to a single-column max.
    val constrainColumn = !sideBySide && cfg.maxContentWidth != Dp.Unspecified

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .let { if (constrainColumn) it.widthIn(max = cfg.maxContentWidth) else it }
                .padding(horizontal = cfg.horizontalGutter),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            layout.views.forEachIndexed { viewIndex, view ->
                renderView(
                    viewIndex = viewIndex,
                    view = view,
                    snapshot = snapshot,
                    registry = registry,
                    cfg = cfg,
                    sideBySide = sideBySide,
                    sectionColumns = sectionColumns,
                    onLongPress = onCardLongPress,
                )
            }
        }
    }
}

/**
 * Emit one view's blocks into the parent [LazyListScope]. Orphan
 * cards (legacy `view.cards`) come first as a single column;
 * sections follow, either stacked (single-column mode) or
 * chunked into rows of [sectionColumns] section-columns
 * (wide mode).
 */
private fun LazyListScope.renderView(
    viewIndex: Int,
    view: ViewLayout,
    snapshot: HaSnapshot,
    registry: ee.schimke.ha.rc.CardRegistry,
    cfg: LayoutConfig,
    sideBySide: Boolean,
    sectionColumns: Int,
    onLongPress: (CardConfig) -> Unit,
) {
    val viewKey = "v$viewIndex"

    if (view.orphanCards.isNotEmpty()) {
        cardRows(
            keyPrefix = "$viewKey-orphan",
            cards = view.orphanCards,
            snapshot = snapshot,
            registry = registry,
            compactPerRow = cfg.compactCardsPerRow,
            onLongPress = onLongPress,
        )
    }

    if (view.sections.isEmpty()) return

    if (!sideBySide) {
        view.sections.forEachIndexed { sectionIndex, section ->
            val sectionKey = "$viewKey-s$sectionIndex"
            section.title?.let { title ->
                item(key = "$sectionKey-title") { SectionHeading(title) }
            }
            cardRows(
                keyPrefix = "$sectionKey",
                cards = section.cards,
                snapshot = snapshot,
                registry = registry,
                // Within a single-column section, keep the same
                // packing rule the orphan path uses — small buttons
                // pair up so a "lights" cluster stays usable on
                // narrow screens.
                compactPerRow = cfg.compactCardsPerRow,
                onLongPress = onLongPress,
            )
        }
    } else {
        view.sections.chunked(sectionColumns).forEachIndexed { rowIndex, sectionRow ->
            item(key = "$viewKey-srow$rowIndex") {
                SectionRow(
                    sections = sectionRow,
                    columns = sectionColumns,
                    snapshot = snapshot,
                    registry = registry,
                    onLongPress = onLongPress,
                )
            }
        }
    }
}

/** Heading for a section title. Shared between single-column and wide layouts. */
@Composable
private fun SectionHeading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
    )
}

/**
 * One row of [columns] section-columns laid out side-by-side. Cards
 * within each section stack vertically — wide mode treats sections
 * as the primary layout unit so we don't compact-pack inside them
 * (a 400-dp section column can show a tile at full width without
 * eyeline pain). If [sections] is shorter than [columns] we pad with
 * empty `weight(1f)` Spacer-equivalents via empty columns so the
 * remaining sections in the next row still align with this one.
 */
@Composable
private fun SectionRow(
    sections: List<SectionLayout>,
    columns: Int,
    snapshot: HaSnapshot,
    registry: ee.schimke.ha.rc.CardRegistry,
    onLongPress: (CardConfig) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        sections.forEach { section ->
            SectionColumn(
                section = section,
                snapshot = snapshot,
                registry = registry,
                onLongPress = onLongPress,
                modifier = Modifier.weight(1f),
            )
        }
        // Reserve weight for missing columns so the row geometry
        // matches its sibling rows when sections.size % columns != 0.
        repeat(columns - sections.size) {
            Box(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SectionColumn(
    section: SectionLayout,
    snapshot: HaSnapshot,
    registry: ee.schimke.ha.rc.CardRegistry,
    onLongPress: (CardConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        section.title?.let { SectionHeading(it) }
        section.cards.forEach { card ->
            val heightDp = remember(card, snapshot) { registry.cardHeightDp(card, snapshot) }
            CardSlot(
                card = card,
                snapshot = snapshot,
                registry = registry,
                onLongPress = onLongPress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heightDp.dp),
            )
        }
    }
}

/**
 * Emit packed rows for [cards] into the parent [LazyListScope].
 * Consecutive [CardWidthClass.Compact] cards group into rows of
 * [compactPerRow]; full-width cards stand alone.
 */
private fun LazyListScope.cardRows(
    keyPrefix: String,
    cards: List<CardConfig>,
    snapshot: HaSnapshot,
    registry: ee.schimke.ha.rc.CardRegistry,
    compactPerRow: Int,
    onLongPress: (CardConfig) -> Unit,
) {
    val rows = packAndChunk(cards, compactPerRow) { c ->
        registry.cardWidthClass(c, snapshot)
    }
    rows.forEachIndexed { rowIndex, row ->
        item(key = "$keyPrefix-r$rowIndex") {
            CardRow(row, snapshot, registry, onLongPress)
        }
    }
}

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
 * Hosts one card's own `.rc` document. Sized by the parent (via
 * `weight(1f)` in compact rows, or `height(...)` in section columns)
 * so the layout decides the slot size, not the RemoteCompose document.
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
            // Stable semantics tag so uiautomator / Compose tests can
            // address card slots by content-description without
            // depending on the (potentially user-localised) card name.
            .semantics(mergeDescendants = true) {
                contentDescription = "dashboard-card:${card.type}"
            }
            // longPressBeforeChild — listens on the Initial pass so it
            // fires even though RemotePreview's pointer-input consumes
            // events on the Main pass for in-document click regions.
            .longPressBeforeChild { onLongPress(card) },
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

/**
 * One emitted dashboard row in the LazyColumn. Either a single
 * full-width card or a cluster of compact cards sharing the row.
 */
private data class DashboardRow(val cards: List<CardConfig>)

/**
 * Group consecutive cards by [CardWidthClass], then chunk Compact
 * groups into rows of [perRow]. Authored order is preserved — a Full
 * card between two Compact runs splits them into two clusters.
 */
private fun packAndChunk(
    cards: List<CardConfig>,
    perRow: Int,
    widthClassOf: (CardConfig) -> CardWidthClass,
): List<DashboardRow> = buildList {
    val pending = mutableListOf<CardConfig>()
    fun flushPending() {
        if (pending.isEmpty()) return
        pending.chunked(perRow).forEach { add(DashboardRow(it.toList())) }
        pending.clear()
    }
    for (card in cards) {
        if (widthClassOf(card) == CardWidthClass.Full) {
            flushPending()
            add(DashboardRow(listOf(card)))
        } else {
            pending += card
        }
    }
    flushPending()
}
