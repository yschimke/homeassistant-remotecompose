@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalGridApi
import androidx.compose.foundation.layout.Grid
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.CardWidthClass
import ee.schimke.ha.rc.LocalCardCaptureEpoch
import ee.schimke.ha.rc.LocalHaActionDispatcher
import ee.schimke.ha.rc.LocalRcDebugBorders
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.cardHeightDp
import ee.schimke.ha.rc.cardWidthClass
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import ee.schimke.terrazzo.LocalTerrazzoGraph
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
    val cache = LocalTerrazzoGraph.current.offlineCache
    // Seed from disk so the first composition shows the last-known
    // dashboard + snapshot instead of a spinner. The polling loop
    // refreshes in place; transient failures don't blank the UI
    // because Ready isn't reset on failure.
    var state by remember(session, urlPath) {
        val cachedDashboard = cache.dashboard(session.baseUrl, urlPath)
        val cachedSnapshot = cache.snapshot(session.baseUrl, urlPath) ?: HaSnapshot()
        val seed: DashboardState =
            cachedDashboard?.let { DashboardState.Ready(it, cachedSnapshot) }
                ?: DashboardState.Loading
        mutableStateOf(seed)
    }

    LaunchedEffect(session, urlPath) {
        // Poll when the session opts in (demo mode does, at ~1s) so
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

    // Demo mode: refresh as soon as a tap mutates state so the next
    // dashboard frame reflects the new override (cover animation,
    // toggle flip) instead of waiting up to a full poll interval.
    val demoEpoch: Long = if (session is DemoHaSession) {
        val epoch by session.actionRouter.epoch.collectAsState()
        LaunchedEffect(session, urlPath, epoch) {
            // Skip the seed value (epoch == 0). Subsequent bumps are
            // user-driven taps and warrant an immediate re-fetch.
            if (epoch > 0L) {
                runCatching { session.loadDashboard(urlPath) }
                    .onSuccess { (dashboard, snapshot) ->
                        state = DashboardState.Ready(dashboard, snapshot)
                    }
            }
        }
        epoch
    } else {
        0L
    }

    val actionDispatcher = rememberDashboardActionDispatcher(session)

    CompositionLocalProvider(
        LocalHaActionDispatcher provides actionDispatcher,
        LocalCardCaptureEpoch provides demoEpoch,
    ) {
        when (val s = state) {
            DashboardState.Loading -> Box(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp)) {
                CircularProgressIndicator()
            }
            is DashboardState.Error -> Box(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp)) {
                Text("Failed: ${s.message}", style = MaterialTheme.typography.bodyMedium)
            }
            is DashboardState.Ready ->
                DashboardList(s.dashboard, s.snapshot, onCardLongPress, contentPadding)
        }
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
    val registry = remember { defaultRegistry().withEnhancedShutter() }
    val layout = remember(dashboard) { buildDashboardLayout(dashboard) }
    val cfg = rememberLayoutConfig()
    val useGridLayout by LocalTerrazzoGraph.current
        .preferencesStore.experimentalGridLayout.collectAsState(initial = false)
    val collapsedMode by LocalTerrazzoGraph.current
        .preferencesStore.collapsedMode.collectAsState(initial = true)

    val sectionColumns = (cfg.maxSectionColumns).coerceAtMost(layout.sectionCount).coerceAtLeast(1)
    val sideBySide = sectionColumns >= 2

    // Per-view "which section is expanded right now". null = none open
    // (user collapsed the open one). Seeded with index 0 for each view
    // when collapsed mode kicks in, so first paint shows above-the-fold
    // content. Re-seeded when [layout] swaps (a different dashboard
    // loaded) so we don't carry stale indices across dashboards. Only
    // consulted by the single-column branch — wide mode shows
    // everything regardless.
    val expandedSectionByView: SnapshotStateMap<Int, Int?> =
        remember(layout, collapsedMode, sideBySide) {
            mutableStateMapOf<Int, Int?>().apply {
                if (collapsedMode && !sideBySide) {
                    layout.views.forEachIndexed { i, view ->
                        if (view.sections.isNotEmpty()) put(i, 0)
                    }
                }
            }
        }

    // Centre + cap the column on Expanded ONLY when the dashboard has
    // no sections — sections drive their own grid width and shouldn't
    // be capped to a single-column max.
    val constrainColumn = !sideBySide && cfg.maxContentWidth != Dp.Unspecified

    val style = LocalThemeStyle.current
    val dark = LocalIsDarkTheme.current
    // Same haTheme each card resolves; computed once here so the
    // section-group surface upstairs and the per-card paint downstairs
    // are derived from a single source.
    val haTheme = remember(style, dark) { haThemeFor(style, dark) }

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
                    useGridLayout = useGridLayout,
                    haTheme = haTheme,
                    collapsedMode = collapsedMode && !sideBySide,
                    expandedSectionIndex = expandedSectionByView[viewIndex],
                    onToggleSection = { sectionIndex ->
                        expandedSectionByView[viewIndex] =
                            if (expandedSectionByView[viewIndex] == sectionIndex) null else sectionIndex
                    },
                    onLongPress = onCardLongPress,
                )
            }
        }
    }
}

/**
 * Wraps a `type: sections` group in a Material 3 surface tinted with
 * [HaTheme.sectionBackground]. Themes that opt into the M3 elevation
 * stack (`Material3`, `Mushroom`, `Kiosk`) set this distinct from
 * `dashboardBackground`, so each section reads as a grouped container.
 * Flat themes (`TerrazzoHome`, `Minimalist`) set it equal to
 * `dashboardBackground`, so the wrap renders as a no-op and preserves
 * the single-surface HA / matt8707 look.
 */
@Composable
private fun SectionGroupSurface(
    haTheme: HaTheme,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        color = haTheme.sectionBackground,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

/**
 * Emit one view's blocks into the parent [LazyListScope]. Orphan
 * cards (legacy `view.cards`) come first as a single column;
 * sections follow, either stacked (single-column mode) or — in wide
 * mode — packed across [sectionColumns] tracks. The wide path has
 * two implementations selected by [useGridLayout]: the legacy
 * `Row` + `chunked` path with manual weight padding, or a single
 * Compose 1.11 `Grid` with one section per cell. Compact / Medium
 * widths render the same either way (both fall through to the
 * single-column branch).
 */
private fun LazyListScope.renderView(
    viewIndex: Int,
    view: ViewLayout,
    snapshot: HaSnapshot,
    registry: ee.schimke.ha.rc.CardRegistry,
    cfg: LayoutConfig,
    sideBySide: Boolean,
    sectionColumns: Int,
    useGridLayout: Boolean,
    haTheme: HaTheme,
    collapsedMode: Boolean,
    expandedSectionIndex: Int?,
    onToggleSection: (Int) -> Unit,
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
        // Single-column: each section becomes a single LazyColumn item
        // wrapped in [SectionGroupSurface] so the title + its cards
        // render as one grouped material container. Cards inside are
        // rendered eagerly (a section is a small handful of cards;
        // laziness still applies across sections).
        view.sections.forEachIndexed { sectionIndex, section ->
            val sectionKey = "$viewKey-s$sectionIndex"
            // Only sections with a title are collapsible — without a
            // heading there's nothing to tap to re-expand them.
            val collapsible = collapsedMode && section.title != null
            val expanded = !collapsible || expandedSectionIndex == sectionIndex
            item(key = sectionKey) {
                SectionGroupSurface(haTheme) {
                    section.title?.let { title ->
                        SectionHeading(
                            title = title,
                            collapsible = collapsible,
                            expanded = expanded,
                            onClick = { onToggleSection(sectionIndex) },
                        )
                    }
                    if (expanded) {
                        val rows = packAndChunk(section.cards, cfg.compactCardsPerRow) { c ->
                            registry.cardWidthClass(c, snapshot)
                        }
                        rows.forEach { row ->
                            CardRow(row, snapshot, registry, onLongPress)
                        }
                    }
                }
            }
        }
    } else if (useGridLayout) {
        item(key = "$viewKey-grid") {
            SectionGrid(
                sections = view.sections,
                columns = sectionColumns,
                snapshot = snapshot,
                registry = registry,
                haTheme = haTheme,
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
                    haTheme = haTheme,
                    onLongPress = onLongPress,
                )
            }
        }
    }
}

/**
 * Heading for a section title. Shared between single-column and wide
 * layouts. In single-column mode with collapsed sections enabled, the
 * heading also acts as the tap target that toggles the section open
 * and shows a chevron reflecting the current state.
 */
@Composable
private fun SectionHeading(
    title: String,
    collapsible: Boolean = false,
    expanded: Boolean = true,
    onClick: () -> Unit = {},
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(top = 8.dp, bottom = 4.dp)
        .let { if (collapsible) it.clickable(onClick = onClick) else it }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (collapsible) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
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
    haTheme: HaTheme,
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
                haTheme = haTheme,
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

/**
 * Experimental wide-mode layout: every section in a view is one cell
 * in a Compose 1.11 [Grid] with [columns] equal-weight column tracks.
 * Replaces the [SectionRow] + `view.sections.chunked(columns)` +
 * `Spacer(weight)` ceremony with a single layout pass that auto-flows
 * sections by row. Per-cell content is the same [SectionColumn]
 * the legacy path uses, so visual parity is the goal: any difference
 * is the Grid layout policy itself, not a different cell renderer.
 *
 * Implicit row tracks default to `GridTrackSize.Auto`, so each row
 * sizes to its tallest section (cards inside still report fixed
 * heights via `cardHeightDp`).
 */
@OptIn(ExperimentalGridApi::class)
@Composable
private fun SectionGrid(
    sections: List<SectionLayout>,
    columns: Int,
    snapshot: HaSnapshot,
    registry: ee.schimke.ha.rc.CardRegistry,
    haTheme: HaTheme,
    onLongPress: (CardConfig) -> Unit,
) {
    Grid(
        modifier = Modifier.fillMaxWidth(),
        config = {
            repeat(columns) { column(1.fr) }
            gap(row = 16.dp, column = 16.dp)
        },
    ) {
        sections.forEach { section ->
            SectionColumn(
                section = section,
                snapshot = snapshot,
                registry = registry,
                haTheme = haTheme,
                onLongPress = onLongPress,
                modifier = Modifier.gridItem(),
            )
        }
    }
}

@Composable
private fun SectionColumn(
    section: SectionLayout,
    snapshot: HaSnapshot,
    registry: ee.schimke.ha.rc.CardRegistry,
    haTheme: HaTheme,
    onLongPress: (CardConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionGroupSurface(haTheme = haTheme, modifier = modifier) {
        section.title?.let { SectionHeading(it) }
        section.cards.forEach { card ->
            // No `height(...)` — the wrap-adaptive player (see
            // `CachedCardPreview` / `WrapAdaptiveRemoteDocumentPlayer`)
            // sizes itself to the document's intrinsic content height
            // after a paint-context warmup, so the slot wraps to the
            // card's actual rendered height instead of pinning per-card
            // via `naturalHeightDp`.
            CardSlot(
                card = card,
                snapshot = snapshot,
                registry = registry,
                onLongPress = onLongPress,
                modifier = Modifier.fillMaxWidth(),
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
 * 2..N compact cards sharing the row equally. The Row sizes itself to
 * the tallest child via Compose's intrinsic measurement; each card's
 * adaptive player wraps to its document's content size, so a row of
 * mixed card types renders as tall as the tallest card with no
 * per-row pinning needed.
 */
@Composable
private fun CardRow(
    row: DashboardRow,
    snapshot: HaSnapshot,
    registry: ee.schimke.ha.rc.CardRegistry,
    onLongPress: (CardConfig) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        row.cards.forEach { card ->
            CardSlot(
                card = card,
                snapshot = snapshot,
                registry = registry,
                onLongPress = onLongPress,
                modifier = Modifier.weight(1f),
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
    val captureEpoch = LocalCardCaptureEpoch.current
    val debugBorders = LocalRcDebugBorders.current
    // The document's paint colours are baked at capture; re-encode when
    // theme flips. Snapshot is deliberately NOT in the cache key —
    // entity values flow into the running player by named binding
    // (see LiveValues in rc-components). [captureEpoch] is the
    // exception: hosts that mutate state out-of-band (demo mode taps)
    // bump it to force a re-encode on the next composition.
    val haTheme = remember(style, dark) { haThemeFor(style, dark) }
    val cacheKey = remember(card, style, dark, captureEpoch) {
        CardSlotCacheKey(card, style, dark, captureEpoch)
    }
    Box(
        modifier = modifier
            // Stable semantics tag so uiautomator / Compose tests can
            // address card slots by content-description without
            // depending on the (potentially user-localised) card name.
            .semantics(mergeDescendants = true) {
                contentDescription = "dashboard-card:${card.type}"
            }
            // longPressBeforeChild — listens on the Initial pass so it
            // fires even though the player's pointer-input consumes
            // events on the Main pass for in-document click regions.
            .longPressBeforeChild { onLongPress(card) }
            .let {
                if (debugBorders) {
                    it.border(1.dp, androidx.compose.ui.graphics.Color(0xFFD32F2F))
                } else it
            },
    ) {
        CachedCardPreview(
            cacheKey = cacheKey,
            profile = androidXExperimentalWrap,
            card = card,
            snapshot = snapshot,
        ) {
            ProvideCardRegistry(registry) {
                ProvideHaTheme(haTheme) {
                    RenderChild(card, snapshot, RemoteModifier.fillMaxWidth())
                }
            }
        }
    }
}

private data class CardSlotCacheKey(
    val card: CardConfig,
    val style: ThemeStyle,
    val dark: Boolean,
    val captureEpoch: Long,
)

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
