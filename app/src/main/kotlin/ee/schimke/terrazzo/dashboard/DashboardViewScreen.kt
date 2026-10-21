@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.dashboard

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Grid
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.JinjaTemplate
import ee.schimke.ha.model.SectionCardFlow
import ee.schimke.ha.model.SectionCardGrid
import ee.schimke.ha.model.SectionsGrid
import ee.schimke.ha.model.SectionsGridLayout
import ee.schimke.ha.model.TemplateBindings
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CardWidthClass
import ee.schimke.ha.rc.HarcTraceSections
import ee.schimke.ha.rc.LocalCardCaptureEpoch
import ee.schimke.ha.rc.LocalHaActionDispatcher
import ee.schimke.ha.rc.LocalRcDebugBorders
import ee.schimke.ha.rc.LovelaceSectionCardFlow
import ee.schimke.ha.rc.LovelaceSectionsGrid
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardIconSignature
import ee.schimke.ha.rc.cardWidthClass
import ee.schimke.ha.rc.cards.cardVisibilitySignature
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ProvideCardStyle
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.cardPalette
import ee.schimke.ha.rc.converterFor
import ee.schimke.ha.rc.framedDesignProperties
import ee.schimke.ha.rc.harcTrace
import ee.schimke.ha.rc.image.CoilBitmapLoader
import ee.schimke.ha.rc.sectionFlowItems
import ee.schimke.terrazzo.LocalHaImageStack
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.logs.referencedEntities
import ee.schimke.terrazzo.core.pin.MobilePinnedSection
import ee.schimke.terrazzo.core.pin.PinStore
import ee.schimke.terrazzo.core.pin.PinnedCardData
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.LatestWins
import ee.schimke.terrazzo.core.session.liveDashboardSnapshots
import ee.schimke.terrazzo.feedback.rememberInteractionFeedback
import ee.schimke.terrazzo.ui.LayoutConfig
import ee.schimke.terrazzo.ui.LocalCardStyle
import ee.schimke.terrazzo.ui.LocalIsDarkTheme
import ee.schimke.terrazzo.ui.LocalThemeStyle
import ee.schimke.terrazzo.ui.rememberLayoutConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Renders every top-level card in a dashboard as an independent `.rc` document hosted in its own
 * `RemotePreview`. This matches the widget-per-card model — a dashboard here is a stack of players,
 * not one giant document.
 *
 * Layout follows the dashboard's own structure as faithfully as the window allows:
 * - **Header** — a sections view's `header` card renders first, centred and at most 700 dp wide, as
 *   HA's view header does.
 * - **Sections grid** — sections are placed with HA's sections-view rules ([SectionsGrid], shared
 *   with the preview harness): as many 320 dp-minimum columns as fit, clamped to the view's
 *   `max_columns` (default 4) and to the sections' total `column_span`, each section spanning
 *   `min(column_span, columns)` in authored row order, the grid at most 500 dp per column and
 *   centred. Section titles sit at the top of each section.
 * - **One column** — when every section spans the full width (always on a phone, and for the common
 *   `column_span: 4` dashboards on small tablets) sections stack with their titles as inline
 *   headings, collapsible, and capped at [LayoutConfig.maxContentWidth] from Medium width up.
 * - **Cards in a section** — HA's section grid ([SectionCardGrid], shared with the preview
 *   harness): 12 columns per view column the section spans, each card spanning its `grid_options`
 *   columns (its type's HA default otherwise: a tile is 6, so two share a row) in authored order.
 *   Legacy `view.cards` still pack consecutive [CardWidthClass.Compact] cards into rows.
 *
 * View titles are not rendered: each top-level dashboard has one "view" in the typical case, and
 * the user already saw the view name in the picker. Multi-view dashboards are an HA tab construct;
 * we'd surface them as tabs, not stacked, when we get to that.
 *
 * A card's `navigate` action (or a markdown link to `/dashboard/view`) scrolls to that view when it
 * is in this dashboard; another dashboard goes to [onOpenDashboard], which returns false when it
 * knows no such dashboard. In demo mode a path naming no dashboard but a view here (the bundled
 * dashboards' links carry their author's dashboard name) still lands on the view; anything else
 * opens on Home Assistant, so a frontend route like `/config/areas` is never taken for a view.
 * [initialViewPath] is the view to show first; [onInitialViewShown] runs once the list has scrolled
 * to it, so a host can stop asking for it. A `more-info` action opens [EntityMoreInfoSheet] on the
 * live snapshot.
 */
@Composable
fun DashboardViewScreen(
  session: HaSession,
  urlPath: String?,
  onCardLongPress: (CardConfig, HaSnapshot) -> Unit,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  readOnly: Boolean = true,
  initialViewPath: String? = null,
  onOpenDashboard: (urlPath: String?, viewPath: String?) -> Boolean = { _, _ -> false },
  onInitialViewShown: () -> Unit = {},
) {
  val cache = LocalTerrazzoGraph.current.offlineCache
  // Seed from disk so the first composition shows the last-known
  // dashboard + snapshot instead of a spinner. The polling loop
  // refreshes in place; transient failures don't blank the UI
  // because Ready isn't reset on failure.
  var state by
    remember(session, urlPath) {
      val cachedDashboard = cache.dashboard(session.baseUrl, urlPath)
      val cachedSnapshot = cache.snapshot(session.baseUrl, urlPath) ?: HaSnapshot()
      val seed: DashboardState =
        cachedDashboard?.let { DashboardState.Ready(it, cachedSnapshot) } ?: DashboardState.Loading
      mutableStateOf(seed)
    }

  // The poll loop, the live feed and a demo tap's refetch all write `state`: only the most recently
  // started load may, so a slow poll landing after a tap's refetch can't bring stale values back.
  val loads = remember(session, urlPath) { LatestWins() }
  val logStore = LocalTerrazzoGraph.current.logStore
  LaunchedEffect(session, urlPath) {
    // Poll when the session opts in (demo mode does, at ~1s) so
    // values visibly change; a one-shot fetch otherwise.
    while (true) {
      try {
        loads.load({ session.loadDashboard(urlPath) }) { (dashboard, snapshot) ->
          state = DashboardState.Ready(dashboard, snapshot)
          // Feed the debug log buffer. The store diffs each
          // snapshot against the previous one and emits a
          // DataUpdate per changed entity, filtered to entities
          // this dashboard actually references.
          logStore.recordDashboardSnapshot(dashboard, snapshot)
        }
      } catch (ce: CancellationException) {
        // Dashboard switch or composition leaving — let the new
        // LaunchedEffect take over. `runCatching` used to absorb
        // this and overwrite state with the cancellation message.
        throw ce
      } catch (t: Throwable) {
        // Log so logcat actually carries the cause. Previously
        // the message was stuffed straight into the UI state
        // with no stack trace, so reports like "Software caused
        // connection abort" arrived with nothing to debug from.
        Log.w(TAG, "loadDashboard($urlPath) failed", t)
        if (state is DashboardState.Loading) {
          state = DashboardState.Error(t.message ?: t::class.simpleName ?: "error")
        }
      }
      val interval = session.refreshIntervalMillis
      if (interval == null) {
        // Live sessions push HA's `state_changed` events instead of being polled: follow them,
        // re-armed by the session on every reconnect, so cards update in place.
        val ready = state as? DashboardState.Ready ?: break
        session.liveDashboardSnapshots(ready.snapshot).collect { snapshot ->
          // A pushed state is the newest there is: it supersedes any load still in flight.
          loads.begin()
          state = DashboardState.Ready(ready.dashboard, snapshot)
          logStore.recordDashboardSnapshot(ready.dashboard, snapshot)
        }
        break
      }
      delay(interval)
    }
  }

  // Demo mode: refresh as soon as a tap mutates state so the next
  // dashboard frame reflects the new override (cover animation,
  // toggle flip) instead of waiting up to a full poll interval.
  val demoEpoch: Long =
    if (session is DemoHaSession) {
      val epoch by session.actionRouter.epoch.collectAsState()
      LaunchedEffect(session, urlPath, epoch) {
        // Skip the seed value (epoch == 0). Subsequent bumps are
        // user-driven taps and warrant an immediate re-fetch.
        if (epoch > 0L) {
          runCatching {
            loads.load({ session.loadDashboard(urlPath) }) { (dashboard, snapshot) ->
              state = DashboardState.Ready(dashboard, snapshot)
              logStore.recordDashboardSnapshot(dashboard, snapshot)
            }
          }
        }
      }
      epoch
    } else {
      0L
    }

  val feedback = rememberInteractionFeedback()
  val latestState = rememberUpdatedState(state)
  val context = androidx.compose.ui.platform.LocalContext.current
  // The view a navigation asked for, until the list has scrolled to it; and the open more-info.
  var pendingView by remember(urlPath) { mutableStateOf(initialViewPath?.let(::ViewTarget)) }
  var moreInfoEntity by remember(urlPath) { mutableStateOf<String?>(null) }
  val navigate: (String) -> Unit = navigate@{ path ->
    val dashboard = (latestState.value as? DashboardState.Ready)?.dashboard
    val target = LovelacePath.parse(path)
    when {
      target == null -> Unit
      target.isIn(urlPath) -> {
        pendingView = ViewTarget(target.view)
        return@navigate
      }
      onOpenDashboard(target.dashboardUrlPath, target.view) -> return@navigate
      // The bundled dashboards' `/<their author's dashboard>/view` links.
      session is DemoHaSession && dashboard != null -> {
        val segment = target.view ?: target.dashboard
        if (dashboard.viewIndexOf(segment) != null) {
          pendingView = ViewTarget(segment)
          return@navigate
        }
      }
    }
    openInBrowser(context, resolveAgainstBase(session.baseUrl, path))
  }
  val actionDispatcher =
    rememberDashboardActionDispatcher(
      session,
      readOnly = readOnly,
      feedback = feedback,
      stateOf = { id ->
        (latestState.value as? DashboardState.Ready)?.snapshot?.states?.get(id)?.state
      },
      onNavigate = navigate,
      onMoreInfo = { moreInfoEntity = it },
    )

  CompositionLocalProvider(
    LocalHaActionDispatcher provides actionDispatcher,
    ee.schimke.ha.rc.LocalHaInputEditing provides (!readOnly && session !is DemoHaSession),
    LocalCardCaptureEpoch provides demoEpoch,
    // Cards showing a relative time ("3 h ago") refresh on this tick, not only on new data.
    ee.schimke.ha.rc.LocalRelativeTimeTick provides ee.schimke.ha.rc.rememberRelativeTimeTick(),
  ) {
    when (val s = state) {
      DashboardState.Loading ->
        Box(
          Modifier.fillMaxSize()
            .padding(contentPadding)
            .consumeWindowInsets(contentPadding)
            .padding(24.dp)
        ) {
          CircularProgressIndicator()
        }
      is DashboardState.Error ->
        Box(
          Modifier.fillMaxSize()
            .padding(contentPadding)
            .consumeWindowInsets(contentPadding)
            .padding(24.dp)
        ) {
          Text("Failed: ${s.message}", style = MaterialTheme.typography.bodyMedium)
        }
      is DashboardState.Ready ->
        DashboardList(
          dashboard = s.dashboard,
          placeholderSession = session,
          snapshot = s.snapshot,
          baseUrl = session.baseUrl,
          dashboardUrlPath = urlPath ?: "",
          onCardLongPress = onCardLongPress,
          contentPadding = contentPadding,
          pendingView = pendingView,
          onViewShown = {
            pendingView = null
            onInitialViewShown()
          },
        )
    }
  }

  moreInfoEntity?.let { entityId ->
    val historyUrl = resolveAgainstBase(session.baseUrl, "/history?entity_id=$entityId")
    EntityMoreInfoSheet(
      entityId = entityId,
      state = (state as? DashboardState.Ready)?.snapshot?.states?.get(entityId),
      onOpenHistory = historyUrl?.let { url -> { openInBrowser(context, url) } },
      onDismiss = { moreInfoEntity = null },
    )
  }
}

/**
 * A view a navigation asked the list to show; a fresh instance per request, so asking for the same
 * view again scrolls again.
 */
private class ViewTarget(val segment: String?)

private fun openInBrowser(context: android.content.Context, url: String?) {
  if (url == null) {
    Log.i(TAG, "Nowhere to open a navigation without a Home Assistant URL")
    return
  }
  val intent =
    android.content
      .Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
      .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
  try {
    context.startActivity(intent)
  } catch (e: android.content.ActivityNotFoundException) {
    Log.w(TAG, "No activity found to open $url", e)
  }
}

private const val TAG = "DashboardView"

private sealed interface DashboardState {
  data object Loading : DashboardState

  data class Error(val message: String) : DashboardState

  data class Ready(val dashboard: Dashboard, val snapshot: HaSnapshot) : DashboardState
}

@Composable
private fun DashboardList(
  dashboard: Dashboard,
  placeholderSession: HaSession,
  snapshot: HaSnapshot,
  baseUrl: String,
  dashboardUrlPath: String,
  onCardLongPress: (CardConfig, HaSnapshot) -> Unit,
  contentPadding: PaddingValues,
  pendingView: ViewTarget? = null,
  onViewShown: () -> Unit = {},
) {
  // The inner card renderers only carry a `(CardConfig) -> Unit`
  // long-press lambda; bind the current snapshot here (DashboardList is
  // the lowest point that holds both) so callers upstairs get the exact
  // frame the user pressed.
  val onLongPress: (CardConfig) -> Unit = { card -> onCardLongPress(card, snapshot) }
  val registry = remember { defaultRegistry().withEnhancedShutter() }
  val layout = remember(dashboard) { buildDashboardLayout(dashboard) }
  val cfg = rememberLayoutConfig()
  val pinStore = LocalTerrazzoGraph.current.pinStore
  val pinScope = rememberCoroutineScope()
  // Long-pressing a section heading plans a placeholder card for this dashboard. Its editor sits at
  // the top of the list, so scroll there: the section pressed may be far down.
  var planningCard by remember(baseUrl, dashboardUrlPath) { mutableStateOf(false) }
  val listState = rememberLazyListState()
  LaunchedEffect(planningCard) { if (planningCard) listState.animateScrollToItem(0) }
  val preferencesStore = LocalTerrazzoGraph.current.preferencesStore
  val useGridLayout by preferencesStore.experimentalGridLayout.collectAsState(initial = false)
  val collapsedMode by preferencesStore.collapsedMode.collectAsState(initial = true)
  // Debug data-visualisation toggles (Settings). Both default off so
  // production renders are untouched; reads are cheap StateFlows.
  val flashOnDataChange by preferencesStore.flashOnDataChange.collectAsState(initial = false)
  val dataGridOverlay by preferencesStore.dataGridOverlay.collectAsState(initial = false)
  // The card designs the user's settings choose from the design server (off when its URL is empty),
  // preloaded at app start so the first frame plays them; null only while that first read runs.
  val preloadedDesigns = rememberPreloadedCardDesigns()
  // Pull the process-singleton image stack (a Metro AppScope binding —
  // see AppGraph.haImageStack). Coil documents that exactly one
  // DiskCache per directory is safe; the stack owns that single
  // instance, plus the OkHttp client whose bearer / LAN-policy
  // interceptors read the current session through provider lambdas.
  // Falls back to the default Coil singleton in preview / test hosts
  // that don't wire a stack — those paths render the URL-fetch
  // first paint via BitmapLoader but skip the live picture-entity
  // override.
  val context = androidx.compose.ui.platform.LocalContext.current
  val haImageStack = LocalHaImageStack.current
  val imageLoader: ImageLoader =
    haImageStack?.imageLoader
      ?: remember(context) { SingletonImageLoader.get(context.applicationContext) }

  // The readable width for one-column content at this window size (none on a phone).
  val readableCap: Dp? = cfg.maxContentWidth.takeIf { it != Dp.Unspecified }

  val style = LocalThemeStyle.current
  val dark = LocalIsDarkTheme.current
  // Same haTheme each card resolves; computed once here so the
  // section-group surface upstairs and the per-card paint downstairs
  // are derived from a single source.
  // The user's card style brings its own palette (Classic follows the app palette), so section
  // surfaces match the cards drawn on them.
  val cardStyle = LocalCardStyle.current
  val haTheme = remember(style, dark, cardStyle) { cardStyle.cardPalette(style, dark) }

  // An uber card style paints the whole dashboard in its own background, so a dark-only style
  // (Nixie, Trinitron) never sits in light gutters; Classic keeps the app's surface as before.
  val dashboardModifier =
    if (cardStyle.id == HaUberTheme.Classic.id) Modifier.fillMaxSize()
    else Modifier.fillMaxSize().background(haTheme.dashboardBackground)
  BoxWithConstraints(modifier = dashboardModifier, contentAlignment = Alignment.TopCenter) {
    // Each sections view is placed with HA's sections-grid rules (shared with the preview
    // harness via [ViewLayout.sectionsGrid]): HA's responsive column gap (8 dp up to a 600 dp
    // window, 32 dp above) as padding either side, columns that fit at HA's 320 dp minimum,
    // clamped to `max_columns` and to the sections' total `column_span`, each section spanning
    // `min(column_span, columns)`. A view whose sections all span the full width reads as one
    // column and takes the readable cap, so a tablet doesn't stretch every card.
    val viewWidthDp = maxWidth.value
    val sectionGrids =
      remember(layout, viewWidthDp, readableCap) {
        layout.views.map { it.sectionsGrid(viewWidthDp, viewWidthDp, readableCap?.value) }
      }
    val singleTrackViews = sectionGrids.map { it.singleTrack }
    // The width each list item gets: the window less the list's gutters and content padding. A
    // one-column section flows its cards into rows at this width before the list lays them out,
    // so each row can be an item of its own (see [renderView]).
    val layoutDirection = LocalLayoutDirection.current
    val listWidth =
      (maxWidth -
          cfg.horizontalGutter * 2 -
          contentPadding.calculateLeftPadding(layoutDirection) -
          contentPadding.calculateRightPadding(layoutDirection))
        .coerceAtLeast(0.dp)

    // Per-view "which section is expanded right now". null = none open
    // (user collapsed the open one). Seeded with index 0 for each view
    // when collapsed mode kicks in, so first paint shows above-the-fold
    // content. Re-seeded when [layout] swaps (a different dashboard
    // loaded) so we don't carry stale indices across dashboards. Only
    // consulted by views that render as one column — a view laid out
    // side by side shows everything regardless.
    val expandedSectionByView: SnapshotStateMap<Int, Int?> =
      remember(layout, collapsedMode, singleTrackViews) {
        mutableStateMapOf<Int, Int?>().apply {
          if (collapsedMode) {
            layout.views.forEachIndexed { i, view ->
              if (view.sections.isNotEmpty() && singleTrackViews[i]) put(i, 0)
            }
          }
        }
      }

    // Hold the cards (briefly, see rememberPreloadedCardDesigns) rather than paint them with the
    // local library's designs and swap to the server's a moment later.
    val cardDesigns =
      preloadedDesigns
        ?: run {
          Box(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp)) {
            CircularProgressIndicator()
          }
          return@BoxWithConstraints
        }
    val cardDesignOverrides by
      remember(context) { CardDesignOverrideStore.of(context).overrides }.collectAsState()
    // A navigation to one of this dashboard's views: open its first section if collapsed mode
    // closed it, and scroll to the view's first item.
    LaunchedEffect(pendingView, layout) {
      val target = pendingView ?: return@LaunchedEffect
      val viewIndex = dashboard.viewIndexOf(target.segment)
      if (viewIndex != null && viewIndex in layout.views.indices) {
        if (
          collapsedMode && singleTrackViews[viewIndex] && expandedSectionByView[viewIndex] == null
        )
          expandedSectionByView[viewIndex] = 0
        // The placeholder briefs are item 0; each view before this one adds its own items.
        val firstItem =
          1 +
            (0 until viewIndex).sumOf { i ->
              layout.views[i].itemCount(
                sectionGrids[i],
                compactPerRow = cfg.compactCardsPerRow,
                stackedSections = { view ->
                  view.stackedSections(
                    sectionGrids[i],
                    listWidth = listWidth,
                    gutter = cfg.horizontalGutter,
                    collapsedMode = collapsedMode,
                    expandedSectionIndex = expandedSectionByView[i],
                    snapshot = snapshot,
                    registry = registry,
                  )
                },
              ) { card ->
                registry.cardWidthClass(card, snapshot)
              }
            }
        listState.animateScrollToItem(firstItem)
      }
      onViewShown()
    }
    CompositionLocalProvider(
      LocalFlashOnDataChange provides flashOnDataChange,
      LocalCardDesigns provides cardDesigns,
      LocalCardDesignOverrides provides cardDesignOverrides,
    ) {
      LazyColumn(
        state = listState,
        modifier =
          Modifier.fillMaxSize()
            .consumeWindowInsets(contentPadding)
            .padding(horizontal = cfg.horizontalGutter),
        contentPadding = contentPadding,
        // Every item after the first opens with [ItemGap] itself, rather than the list spacing
        // them: a one-column section's items join up with no gap between them.
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        item(key = "local-card-briefs") {
          DashboardPlaceholders(
            session = placeholderSession,
            urlPath = dashboardUrlPath.takeIf { it.isNotEmpty() },
            planning = planningCard,
            onPlanningDone = { planningCard = false },
          )
        }
        layout.views.forEachIndexed { viewIndex, view ->
          val grid = sectionGrids[viewIndex]
          renderView(
            viewIndex = viewIndex,
            view = view,
            grid = grid,
            listWidth = listWidth,
            snapshot = snapshot,
            registry = registry,
            cfg = cfg,
            orphanMaxWidth = readableCap ?: Dp.Infinity,
            useGridLayout = useGridLayout,
            haTheme = haTheme,
            collapsedMode = collapsedMode && grid.singleTrack,
            expandedSectionIndex = expandedSectionByView[viewIndex],
            onToggleSection = { sectionIndex ->
              expandedSectionByView[viewIndex] =
                if (expandedSectionByView[viewIndex] == sectionIndex) null else sectionIndex
            },
            baseUrl = baseUrl,
            imageLoader = imageLoader,
            onLongPress = onLongPress,
            pinContext =
              SectionPinContext(
                baseUrl = baseUrl,
                dashboardUrlPath = dashboardUrlPath,
                viewPath = view.viewPath,
                pinStore = pinStore,
                onPinAction = { action -> pinScope.launch { action() } },
                onPlanCard = { planningCard = true },
              ),
          )
        }
      }
    }
    if (dataGridOverlay) {
      DataGridOverlay(
        dashboard = dashboard,
        snapshot = snapshot,
        onClose = { pinScope.launch { preferencesStore.setDataGridOverlay(false) } },
        contentPadding = contentPadding,
      )
    }
  }
}

/**
 * Wraps a `type: sections` group in a Material 3 surface tinted with [HaTheme.sectionBackground].
 * Themes that opt into the M3 elevation stack (`Material3`, `Mushroom`, `Kiosk`) set this distinct
 * from `dashboardBackground`, so each section reads as a grouped container. Flat themes
 * (`TerrazzoHome`, `Minimalist`) set it equal to `dashboardBackground`, so the wrap renders as a
 * no-op and preserves the single-surface HA / matt8707 look.
 */
@Composable
private fun SectionGroupSurface(
  haTheme: HaTheme,
  modifier: Modifier = Modifier,
  shape: RoundedCornerShape = SectionShape,
  contentTop: Dp = 0.dp,
  content: @Composable () -> Unit,
) {
  Surface(color = haTheme.sectionBackground, shape = shape, modifier = modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(start = 8.dp, top = contentTop, end = 8.dp),
      verticalArrangement = Arrangement.spacedBy(SectionContentGap),
    ) {
      content()
    }
  }
}

private val SectionCorner = 20.dp
private val SectionShape = RoundedCornerShape(SectionCorner)

/** The surface's horizontal padding either side of a section's heading and cards. */
private val SectionInset = 8.dp

/** Between a section's heading and its cards. */
private val SectionContentGap = 12.dp

/** Between the dashboard list's blocks: the placeholder briefs, a header, a row, a section. */
private val ItemGap = 12.dp

/**
 * A one-column section as list items: its heading (null when it shows none) and its cards' rows, as
 * [SectionCardFlow] fills them at the section's width. Hidden cards are in no row.
 */
private class StackedSection(val heading: SectionHeadingModel?, val rows: List<List<CardConfig>>) {
  val itemCount: Int
    get() = (if (heading != null) 1 else 0) + rows.size
}

/**
 * [ViewLayout.sections] of a [SectionsGridLayout.singleTrack] view as [renderView] stacks them in a
 * list [listWidth] wide: collapsed ones show only their heading.
 */
private fun ViewLayout.stackedSections(
  grid: SectionsGridLayout,
  listWidth: Dp,
  gutter: Dp,
  collapsedMode: Boolean,
  expandedSectionIndex: Int?,
  snapshot: HaSnapshot,
  registry: ee.schimke.ha.rc.CardRegistry,
): List<StackedSection> {
  val sectionsPadding = sectionsPadding(grid, gutter)
  val sectionWidth = minOf(listWidth - sectionsPadding * 2, grid.maxWidthDp.dp)
  val cardsWidth = (sectionWidth - SectionInset * 2).coerceAtLeast(0.dp)
  return sections.mapIndexed { sectionIndex, section ->
    // In collapsed mode every section must remain reachable, even when authored without an
    // explicit title. Untitled sections get a fallback heading so users can re-expand them.
    val expanded = !collapsedMode || expandedSectionIndex == sectionIndex
    val headingModel = resolveSectionHeading(section, sectionIndex)
    val heading = headingModel.takeIf { collapsedMode || section.title != null }
    val cards =
      when {
        !expanded -> emptyList()
        heading != null -> headingModel.visibleCards
        else -> section.cards
      }
    val rows =
      SectionCardFlow.rows(
          sectionFlowItems(cards, snapshot, registry),
          cardsWidth.value,
          SectionCardGrid.COLUMN_GAP_DP,
        )
        .map { row -> row.indices.map(cards::get) }
    StackedSection(heading, rows)
  }
}

/** HA pads the sections wrapper by one column gap either side; the list already has [gutter]. */
private fun sectionsPadding(grid: SectionsGridLayout, gutter: Dp): Dp =
  (grid.horizontalPaddingDp.dp - gutter).coerceAtLeast(0.dp)

/**
 * Emit one view's blocks into the parent [LazyListScope]: the view header's card, then orphan cards
 * (legacy `view.cards`) as a single column, then the sections as [grid] placed them. A
 * [SectionsGridLayout.singleTrack] grid (every section full width — always the case on a phone)
 * stacks the sections, collapsible and with compact cards packed into rows; otherwise one
 * [LovelaceSectionsGrid] item lays them out side by side honouring `column_span`. [useGridLayout]
 * swaps that for the experimental Compose `Grid`, fed the same spans.
 */
private fun LazyListScope.renderView(
  viewIndex: Int,
  view: ViewLayout,
  grid: SectionsGridLayout,
  listWidth: Dp,
  snapshot: HaSnapshot,
  registry: ee.schimke.ha.rc.CardRegistry,
  cfg: LayoutConfig,
  orphanMaxWidth: Dp,
  useGridLayout: Boolean,
  haTheme: HaTheme,
  collapsedMode: Boolean,
  expandedSectionIndex: Int?,
  onToggleSection: (Int) -> Unit,
  baseUrl: String?,
  imageLoader: ImageLoader,
  onLongPress: (CardConfig) -> Unit,
  pinContext: SectionPinContext,
) {
  val viewKey = "v$viewIndex"
  val contentWidth = grid.maxWidthDp.dp
  val sectionsPadding = sectionsPadding(grid, cfg.horizontalGutter)
  val contentModifier =
    Modifier.padding(top = ItemGap)
      .padding(horizontal = sectionsPadding)
      .widthIn(max = contentWidth)
      .fillMaxWidth()

  val header = view.header
  val headerCard = header?.card
  if (headerCard != null) {
    item(key = "$viewKey-header") {
      ViewHeaderCard(
        card = headerCard,
        centered = header.centered,
        snapshot = snapshot,
        registry = registry,
        baseUrl = baseUrl,
        imageLoader = imageLoader,
        onLongPress = onLongPress,
        modifier = contentModifier,
      )
    }
  }

  if (view.orphanCards.isNotEmpty()) {
    cardRows(
      keyPrefix = "$viewKey-orphan",
      cards = view.orphanCards,
      snapshot = snapshot,
      registry = registry,
      compactPerRow = cfg.compactCardsPerRow,
      baseUrl = baseUrl,
      imageLoader = imageLoader,
      onLongPress = onLongPress,
      modifier = Modifier.padding(top = ItemGap).widthIn(max = orphanMaxWidth).fillMaxWidth(),
    )
  }

  if (view.sections.isEmpty()) return

  if (grid.singleTrack) {
    // One column: each section is its heading's item, then one item per row of its cards, drawn as
    // pieces of one [SectionGroupSurface] (rounded only at the section's top and bottom). Not one
    // item per section: every card in it would compose, subcompose its player and measure in the
    // same frame — a dropped frame each time a section scrolled on screen, which prefetch can't
    // spread out. Row by row, the list composes only what comes into view, and prefetches the next
    // row ahead of it.
    val stacked =
      view.stackedSections(
        grid,
        listWidth = listWidth,
        gutter = cfg.horizontalGutter,
        collapsedMode = collapsedMode,
        expandedSectionIndex = expandedSectionIndex,
        snapshot = snapshot,
        registry = registry,
      )
    val pieceModifier = Modifier.padding(horizontal = sectionsPadding).widthIn(max = contentWidth)
    view.sections.forEachIndexed { sectionIndex, section ->
      val sectionKey = "$viewKey-s$sectionIndex"
      val pieces = stacked[sectionIndex]
      val heading = pieces.heading
      val lastPiece = pieces.itemCount - 1
      fun shape(piece: Int) =
        RoundedCornerShape(
          topStart = if (piece == 0) SectionCorner else 0.dp,
          topEnd = if (piece == 0) SectionCorner else 0.dp,
          bottomStart = if (piece == lastPiece) SectionCorner else 0.dp,
          bottomEnd = if (piece == lastPiece) SectionCorner else 0.dp,
        )
      fun Modifier.gapAbove(piece: Int) = if (piece == 0) padding(top = ItemGap) else this
      if (heading != null) {
        item(key = "$sectionKey-heading") {
          SectionGroupSurface(haTheme, modifier = pieceModifier.gapAbove(0), shape = shape(0)) {
            PinnableSectionHeading(
              title = heading.title,
              collapsible = collapsedMode,
              expanded = !collapsedMode || expandedSectionIndex == sectionIndex,
              onClick = { onToggleSection(sectionIndex) },
              pinContext = pinContext,
              sectionIndex = sectionIndex,
              section = section,
            )
          }
        }
      }
      val firstRowPiece = if (heading != null) 1 else 0
      pieces.rows.forEachIndexed { rowIndex, row ->
        val piece = firstRowPiece + rowIndex
        item(key = "$sectionKey-r$rowIndex") {
          SectionGroupSurface(
            haTheme,
            modifier = pieceModifier.gapAbove(piece),
            shape = shape(piece),
            // The heading's gap to the cards, or the section grid's between rows.
            contentTop =
              when {
                rowIndex > 0 -> SectionCardGrid.ROW_GAP_DP.dp
                heading != null -> SectionContentGap
                else -> 0.dp
              },
          ) {
            SectionCards(
              cards = row,
              snapshot = snapshot,
              registry = registry,
              baseUrl = baseUrl,
              imageLoader = imageLoader,
              onLongPress = onLongPress,
            )
          }
        }
      }
    }
  } else if (useGridLayout) {
    item(key = "$viewKey-grid") {
      SectionGrid(
        sections = view.sections,
        grid = grid,
        snapshot = snapshot,
        registry = registry,
        haTheme = haTheme,
        baseUrl = baseUrl,
        imageLoader = imageLoader,
        onLongPress = onLongPress,
        pinContext = pinContext,
        modifier = contentModifier,
      )
    }
  } else {
    item(key = "$viewKey-sections") {
      LovelaceSectionsGrid(
        grid = grid,
        modifier = contentModifier,
      ) {
        view.sections.forEachIndexed { sectionIndex, section ->
          SectionColumn(
            section = section,
            sectionIndex = sectionIndex,
            snapshot = snapshot,
            registry = registry,
            haTheme = haTheme,
            baseUrl = baseUrl,
            imageLoader = imageLoader,
            onLongPress = onLongPress,
            pinContext = pinContext,
          )
        }
      }
    }
  }
}

/**
 * How many [LazyListScope] items [renderView] emits for this view: its header, its orphan card
 * rows, then each stacked section's heading and card rows ([stackedSections]) in a single track, or
 * one item for the side-by-side grid.
 */
private fun ViewLayout.itemCount(
  grid: SectionsGridLayout,
  compactPerRow: Int,
  stackedSections: (ViewLayout) -> List<StackedSection>,
  widthClassOf: (CardConfig) -> CardWidthClass,
): Int {
  val header = if (header?.card != null) 1 else 0
  val orphans =
    if (orphanCards.isEmpty()) 0 else packAndChunk(orphanCards, compactPerRow, widthClassOf).size
  val sections =
    when {
      sections.isEmpty() -> 0
      grid.singleTrack -> stackedSections(this).sumOf { it.itemCount }
      else -> 1
    }
  return header + orphans + sections
}

/**
 * A sections view's header card (HA's `hui-view-header`): at most
 * [SectionsGrid.HEADER_MAX_WIDTH_DP] wide, centred unless the header's `layout` is `start`.
 */
@Composable
private fun ViewHeaderCard(
  card: CardConfig,
  centered: Boolean,
  snapshot: HaSnapshot,
  registry: ee.schimke.ha.rc.CardRegistry,
  baseUrl: String?,
  imageLoader: ImageLoader,
  onLongPress: (CardConfig) -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier.padding(top = 8.dp),
    contentAlignment = if (centered) Alignment.TopCenter else Alignment.TopStart,
  ) {
    key(card) {
      CardSlot(
        card = card,
        snapshot = snapshot,
        registry = registry,
        baseUrl = baseUrl,
        imageLoader = imageLoader,
        onLongPress = onLongPress,
        modifier = Modifier.widthIn(max = SectionsGrid.HEADER_MAX_WIDTH_DP.dp).fillMaxWidth(),
      )
    }
  }
}

private data class SectionHeadingModel(val title: String, val visibleCards: List<CardConfig>)

private val SECTION_HEADER_CARD_TYPES: Set<String> = setOf("heading", "button", "tile", "entity")

private fun resolveSectionHeading(section: SectionLayout, sectionIndex: Int): SectionHeadingModel {
  section.title?.let {
    return SectionHeadingModel(title = it, visibleCards = section.cards)
  }
  val firstCard = section.cards.firstOrNull()
  if (firstCard != null && firstCard.canActAsSectionHeader()) {
    return SectionHeadingModel(
      title = firstCard.headingLikeTitle(),
      visibleCards = section.cards.drop(1),
    )
  }
  return SectionHeadingModel(title = "Section ${sectionIndex + 1}", visibleCards = section.cards)
}

private fun CardConfig.canActAsSectionHeader(): Boolean {
  if (type !in SECTION_HEADER_CARD_TYPES) return false
  val entity = raw["entity"]?.jsonPrimitive?.contentOrNull
  val entities = raw["entities"]?.jsonArray?.isNotEmpty() == true
  return entity.isNullOrBlank() && !entities && headingLikeTitle().isNotBlank()
}

private fun CardConfig.headingLikeTitle(): String =
  raw["title"]?.jsonPrimitive?.contentOrNull
    ?: raw["heading"]?.jsonPrimitive?.contentOrNull
    ?: raw["name"]?.jsonPrimitive?.contentOrNull
    ?: type

/**
 * Heading for a section title. Shared between single-column and wide layouts. In single-column mode
 * with collapsed sections enabled, the heading also acts as the tap target that toggles the section
 * open and shows a chevron reflecting the current state. A long press runs [onLongClick] (planning
 * a placeholder card) when one is given.
 */
@Composable
internal fun SectionHeading(
  title: String,
  collapsible: Boolean = false,
  expanded: Boolean = true,
  onClick: () -> Unit = {},
  onLongClick: (() -> Unit)? = null,
  trailing: @Composable (() -> Unit)? = null,
) {
  val haptics = LocalHapticFeedback.current
  val rowModifier =
    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).let {
      when {
        onLongClick != null ->
          it.combinedClickable(
            onClickLabel = if (collapsible) (if (expanded) "Collapse" else "Expand") else null,
            onLongClickLabel = "Plan a card",
            onLongClick = {
              haptics.performHapticFeedback(HapticFeedbackType.LongPress)
              onLongClick()
            },
            onClick = { if (collapsible) onClick() },
          )
        collapsible -> it.clickable(onClick = onClick)
        else -> it
      }
    }
  Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
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
    trailing?.invoke()
  }
}

/**
 * Context plumbed from [DashboardList] down through every section renderer. Carries enough to
 * compute a stable pin key ([PinStore.sectionKey]) and to dispatch pin/unpin coroutines, plus
 * [onPlanCard], which a long press on a section heading runs to plan a placeholder card.
 */
private data class SectionPinContext(
  val baseUrl: String,
  val dashboardUrlPath: String,
  val viewPath: String,
  val pinStore: PinStore,
  val onPinAction: (suspend () -> Unit) -> Unit,
  val onPlanCard: () -> Unit,
)

/**
 * [SectionHeading] plus a pin/unpin toggle. Stable key derives from the section's position in the
 * dashboard so the same section keeps the same key across cold launches; unpin → re-pin is a no-op
 * for downstream slot references.
 */
@Composable
private fun PinnableSectionHeading(
  title: String,
  pinContext: SectionPinContext,
  sectionIndex: Int,
  section: SectionLayout,
  collapsible: Boolean = false,
  expanded: Boolean = true,
  onClick: () -> Unit = {},
) {
  val key =
    remember(pinContext, sectionIndex) {
      PinStore.sectionKey(
        baseUrl = pinContext.baseUrl,
        dashboardUrlPath = pinContext.dashboardUrlPath,
        viewPath = pinContext.viewPath,
        sectionIndex = sectionIndex,
      )
    }
  val isPinned by pinContext.pinStore.isSectionPinned(key).collectAsState(initial = false)
  SectionHeading(
    title = title,
    collapsible = collapsible,
    expanded = expanded,
    onClick = onClick,
    onLongClick = pinContext.onPlanCard,
    trailing = {
      IconButton(
        onClick = {
          pinContext.onPinAction {
            if (isPinned) {
              pinContext.pinStore.unpinSection(key)
            } else {
              pinContext.pinStore.pinSection(
                MobilePinnedSection(
                  key = key,
                  baseUrl = pinContext.baseUrl,
                  dashboardUrlPath = pinContext.dashboardUrlPath,
                  viewPath = pinContext.viewPath,
                  sectionIndex = sectionIndex,
                  title = title,
                  cards = section.cards.map { it.toPinnedData() },
                )
              )
            }
          }
        }
      ) {
        Icon(
          imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
          contentDescription = if (isPinned) "Unpin section from Wear" else "Pin section to Wear",
          tint = MaterialTheme.colorScheme.onSurface,
        )
      }
    },
  )
}

/**
 * Capture a [CardConfig] into the pin store's neutral [PinnedCardData] shape. Title and primary
 * entity are pulled from the same JSON keys `MobileWearSyncManager.toSummary` uses so the wear-side
 * rendering is consistent with what the live publish path produces.
 */
internal fun CardConfig.toPinnedData(): PinnedCardData {
  val raw = this.raw
  val title =
    raw["title"]?.jsonPrimitive?.contentOrNull
      ?: raw["heading"]?.jsonPrimitive?.contentOrNull
      ?: raw["name"]?.jsonPrimitive?.contentOrNull
      ?: this.type
  val primary =
    raw["entity"]?.jsonPrimitive?.contentOrNull
      ?: raw["entities"]?.jsonArray?.firstOrNull()?.let {
        (it as? JsonPrimitive)?.contentOrNull
          ?: (it as? JsonObject)?.get("entity")?.jsonPrimitive?.contentOrNull
      }
      ?: ""
  return PinnedCardData(
    type = this.type,
    title = title,
    primaryEntity = primary,
    rawJson = pinJson.encodeToString(JsonObject.serializer(), raw),
  )
}

private val pinJson = Json { ignoreUnknownKeys = true }

/**
 * Experimental wide-mode layout: the sections in one Compose 1.11 [Grid] with the same column count
 * and spans [SectionsGrid] computed, left to the `Grid`'s own auto-placement. Per-cell content is
 * the same [SectionColumn] [LovelaceSectionsGrid] uses, so any difference is the layout policy
 * itself, not a different cell renderer.
 *
 * Implicit row tracks default to `GridTrackSize.Auto`, so each row sizes to its tallest section.
 */
@Composable
private fun SectionGrid(
  sections: List<SectionLayout>,
  grid: SectionsGridLayout,
  snapshot: HaSnapshot,
  registry: ee.schimke.ha.rc.CardRegistry,
  haTheme: HaTheme,
  baseUrl: String?,
  imageLoader: ImageLoader,
  onLongPress: (CardConfig) -> Unit,
  pinContext: SectionPinContext,
  modifier: Modifier = Modifier,
) {
  Grid(
    modifier = modifier,
    config = {
      repeat(grid.columnCount) { column(1.fr) }
      gap(row = grid.rowGapDp.dp, column = grid.columnGapDp.dp)
    },
  ) {
    sections.forEachIndexed { sectionIndex, section ->
      val placement = grid.placements[sectionIndex]
      SectionColumn(
        section = section,
        sectionIndex = sectionIndex,
        snapshot = snapshot,
        registry = registry,
        haTheme = haTheme,
        baseUrl = baseUrl,
        imageLoader = imageLoader,
        onLongPress = onLongPress,
        pinContext = pinContext,
        modifier =
          Modifier.gridItem(rowSpan = placement.rowSpan, columnSpan = placement.columnSpan),
      )
    }
  }
}

@Composable
private fun SectionColumn(
  section: SectionLayout,
  sectionIndex: Int,
  snapshot: HaSnapshot,
  registry: ee.schimke.ha.rc.CardRegistry,
  haTheme: HaTheme,
  baseUrl: String?,
  imageLoader: ImageLoader,
  onLongPress: (CardConfig) -> Unit,
  pinContext: SectionPinContext,
  modifier: Modifier = Modifier,
) {
  SectionGroupSurface(haTheme = haTheme, modifier = modifier) {
    section.title?.let {
      PinnableSectionHeading(
        title = it,
        pinContext = pinContext,
        sectionIndex = sectionIndex,
        section = section,
      )
    }
    SectionCards(
      cards = section.cards,
      snapshot = snapshot,
      registry = registry,
      baseUrl = baseUrl,
      imageLoader = imageLoader,
      onLongPress = onLongPress,
    )
  }
}

/**
 * One section's cards in HA's order, laid out by their own designs' sizes
 * ([LovelaceSectionCardFlow], shared with the preview harness; design principle 9): rows fill in
 * authored order while every card keeps its minimum width, and re-flow when the section's width
 * changes. Each card is measured at exactly its share of the row, so its converter picks the
 * matching width tier.
 */
@Composable
private fun SectionCards(
  cards: List<CardConfig>,
  snapshot: HaSnapshot,
  registry: ee.schimke.ha.rc.CardRegistry,
  baseUrl: String?,
  imageLoader: ImageLoader,
  onLongPress: (CardConfig) -> Unit,
) {
  val items = remember(cards, snapshot, registry) { sectionFlowItems(cards, snapshot, registry) }
  LovelaceSectionCardFlow(items = items, modifier = Modifier.fillMaxWidth()) {
    cards.forEach { card ->
      // Key by the card config so each slot's hosted player is tied
      // to its card identity, not its position in the section — a
      // reused slot must not keep a previous card's player state.
      key(card) {
        // No `height(...)` — `CachedCardPreview`'s Compose
        // Multiplatform player lays the document out as Compose
        // nodes, so the slot wraps to the card's actual rendered
        // height instead of pinning per-card via `naturalHeightDp`.
        CardSlot(
          card = card,
          snapshot = snapshot,
          registry = registry,
          baseUrl = baseUrl,
          imageLoader = imageLoader,
          onLongPress = onLongPress,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

/**
 * Emit packed rows for [cards] into the parent [LazyListScope]. Consecutive
 * [CardWidthClass.Compact] cards group into rows of [compactPerRow]; full-width cards stand alone.
 */
private fun LazyListScope.cardRows(
  keyPrefix: String,
  cards: List<CardConfig>,
  snapshot: HaSnapshot,
  registry: ee.schimke.ha.rc.CardRegistry,
  compactPerRow: Int,
  baseUrl: String?,
  imageLoader: ImageLoader,
  onLongPress: (CardConfig) -> Unit,
  modifier: Modifier = Modifier,
) {
  val rows = packAndChunk(cards, compactPerRow) { c -> registry.cardWidthClass(c, snapshot) }
  rows.forEachIndexed { rowIndex, row ->
    item(key = "$keyPrefix-r$rowIndex") {
      CardRow(row, snapshot, registry, baseUrl, imageLoader, onLongPress, modifier)
    }
  }
}

/**
 * One LazyColumn item — either a single full-width card or a row of 2..N compact cards sharing the
 * row equally. The Row sizes itself to the tallest child via Compose's intrinsic measurement; each
 * card's adaptive player wraps to its document's content size, so a row of mixed card types renders
 * as tall as the tallest card with no per-row pinning needed.
 */
@Composable
private fun CardRow(
  row: DashboardRow,
  snapshot: HaSnapshot,
  registry: ee.schimke.ha.rc.CardRegistry,
  baseUrl: String?,
  imageLoader: ImageLoader,
  onLongPress: (CardConfig) -> Unit,
  modifier: Modifier = Modifier.fillMaxWidth(),
) {
  Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    row.cards.forEach { card ->
      // Key by the card config so a slot never inherits an
      // unrelated card's hosted player when repacking shifts cards
      // between rows (e.g. a width-class change re-chunks the row).
      // Without it the reused player would keep the prior
      // card's view — the same staleness seen on dashboard switch.
      key(card) {
        CardSlot(
          card = card,
          snapshot = snapshot,
          registry = registry,
          baseUrl = baseUrl,
          imageLoader = imageLoader,
          onLongPress = onLongPress,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

/** Read-only dashboard rendering for the designer, independent of native launcher support. */
@Composable
internal fun StudioCardPreview(session: HaSession, card: CardConfig, snapshot: HaSnapshot) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val registry = remember { defaultRegistry().withEnhancedShutter() }
  val imageLoader =
    LocalHaImageStack.current?.imageLoader
      ?: remember(context) { SingletonImageLoader.get(context.applicationContext) }
  CompositionLocalProvider(
    LocalHaActionDispatcher provides ee.schimke.ha.rc.NoOpHaActionDispatcher
  ) {
    CardSlot(
      card,
      snapshot,
      registry,
      session.baseUrl,
      imageLoader,
      {},
      Modifier.fillMaxWidth().semantics { contentDescription = "Current card preview" },
    )
  }
}

@Composable
private fun CardSlot(
  card: CardConfig,
  snapshot: HaSnapshot,
  registry: ee.schimke.ha.rc.CardRegistry,
  baseUrl: String?,
  imageLoader: ImageLoader,
  onLongPress: (CardConfig) -> Unit,
  modifier: Modifier = Modifier,
) {
  val style = LocalThemeStyle.current
  val dark = LocalIsDarkTheme.current
  val captureEpoch = LocalCardCaptureEpoch.current
  val debugBorders = LocalRcDebugBorders.current
  // Debug flash-on-change: fingerprint the entity states this card
  // renders so the slot can flash when any of them move. Refs are
  // resolved once per card; the signature is only built when the
  // feature is on, so production pays a single set computation.
  val flashOnChange = LocalFlashOnDataChange.current
  val flashRefs = remember(card) { referencedEntities(card) }
  val flashSignature = if (flashOnChange) cardStateSignature(snapshot, flashRefs) else null
  // The document's paint colours are baked at capture; re-encode when
  // theme flips. Snapshot is deliberately NOT in the cache key —
  // entity values flow into the running player by named binding
  // (see LiveValues in rc-components). [captureEpoch] is the
  // exception: hosts that mutate state out-of-band (demo mode taps)
  // bump it to force a re-encode on the next composition.
  // The user's card style, unless they gave this card, or every card of its type, its own.
  val override = LocalCardDesignOverrides.current.forCard(card)
  val cardStyle = override?.cardStyle?.let(HaUberTheme::byId) ?: LocalCardStyle.current
  val haTheme = remember(style, dark, cardStyle) { cardStyle.cardPalette(style, dark) }
  // Markdown templates are evaluated in-process and baked into the
  // document (full markdown fidelity), so unlike entity state they DO
  // belong in the cache key: when the rendered text changes, the key
  // changes and the card re-encodes. Empty for non-template cards, so
  // their keys stay stable.
  val templateValues =
    remember(card, snapshot) {
      TemplateBindings.cardTemplates(card).associate {
        it.key to JinjaTemplate.render(it.template, snapshot)
      }
    }
  // Cards whose live content is baked rather than bound (forecast strips,
  // history sparklines, to-do/calendar/logbook lists, arc fills) advertise a
  // data signature; folding it into the cache key re-encodes the document when
  // that content moves. Null for binding-backed cards, whose key stays stable
  // while named-binding pushes carry the updates (see CachedCardPreview).
  // `liveBindings` is the inverse flag: a re-encode card skips the push so its
  // per-card-formatted bake isn't clobbered by a raw value.
  // A relative-time label ("3 h ago") is in the signature too; the tick re-reads it as the clock
  // moves. Zero (never changing) for cards that show no relative time.
  val tickEntities = remember(card) { ee.schimke.ha.rc.cardEntityIds(card) }
  val relativeTimeTick = ee.schimke.ha.rc.relativeTimeTickFor(tickEntities, snapshot)
  val dataSignature =
    remember(card, snapshot, relativeTimeTick) { registry.cardDataSignature(card, snapshot) }
  // Glyphs that follow an entity's state beyond on/off (a sun's day / night icon, an alarm panel's
  // armed / disarmed shield) are baked, so they re-encode the card when they change. Values still
  // arrive as bindings: this joins the key without turning `liveBindings` off.
  val iconSignature = remember(card, snapshot) { cardIconSignature(card, snapshot) }
  // A conditional showing or hiding changes what the card draws (and how a grid lays out its
  // cells), so it re-encodes the card too, with bindings still on.
  val visibilitySignature = remember(card, snapshot) { cardVisibilitySignature(card, snapshot) }
  val cacheKey =
    remember(
      card,
      style,
      dark,
      cardStyle,
      captureEpoch,
      templateValues,
      dataSignature,
      iconSignature,
      visibilitySignature,
    ) {
      CardSlotCacheKey(
        card,
        style,
        dark,
        cardStyle.id,
        captureEpoch,
        templateValues,
        dataSignature,
        iconSignature,
        visibilitySignature,
      )
    }
  val context = androidx.compose.ui.platform.LocalContext.current
  // Templatable cards always render from a card design: the design server's when one is cached for
  // this card's key, the local library's otherwise. The card's generation properties key the
  // template, so value-only snapshot changes keep the same document and arrive as bindings.
  val cardDesigns = LocalCardDesigns.current
  // A conditional or entity-filter card renders as the card it currently shows.
  val designCard = CardDesignTemplates.designCard(card, snapshot, registry)
  val designShape =
    registry.converterFor(designCard.type)?.framedDesignProperties(designCard, snapshot)
  val template =
    remember(designCard, designShape, cardDesigns, style, dark, cardStyle, override) {
      harcTrace(HarcTraceSections.CARD_TEMPLATE) {
        cardTemplateFor(
          context,
          designCard,
          snapshot,
          cardDesigns,
          style,
          dark,
          registry,
          cardStyle,
          variant = override?.variant,
        )
      }
    }
  // A card with no template draws through its converter, or not at all: count which, once per card.
  LaunchedEffect(designCard, template == null, cardStyle) {
    if (template == null) {
      CardUsage.record(
        designCard,
        cardType = designCard.type,
        variant = ee.schimke.ha.design.DesignKey.DEFAULT_VARIANT,
        style = cardStyle.id,
        source =
          if (registry.converterFor(designCard.type) == null)
            ee.schimke.ha.client.design.UsageSource.Unsupported
          else ee.schimke.ha.client.design.UsageSource.Converter,
      )
    }
  }
  val bitmapLoader =
    remember(context, baseUrl, imageLoader) {
      CoilBitmapLoader(context.applicationContext, imageLoader = imageLoader, baseUrl = baseUrl)
    }
  Box(
    modifier =
      modifier
        // Stable semantics tag so uiautomator / Compose tests can
        // address card slots by content-description without
        // depending on the (potentially user-localised) card name.
        .semantics(mergeDescendants = true) { contentDescription = "dashboard-card:${card.type}" }
        // longPressBeforeChild — listens on the Initial pass so it
        // fires even though the player's pointer-input consumes
        // events on the Main pass for in-document click regions.
        .longPressBeforeChild { onLongPress(card) }
        .let {
          if (debugBorders) {
            it.border(1.dp, androidx.compose.ui.graphics.Color(0xFFD32F2F))
          } else it
        }
  ) {
    CachedCardPreview(
      cacheKey = cacheKey,
      profile = androidXExperimentalWrap,
      card = card,
      snapshot = snapshot,
      liveBindings = dataSignature == null,
      bitmapLoader = bitmapLoader,
      template = template,
    ) {
      ProvideCardRegistry(registry) {
        ProvideCardStyle(cardStyle, haTheme, dark) {
          RenderChild(card, snapshot, RemoteModifier.fillMaxWidth())
        }
      }
    }
    if (flashSignature != null) {
      CardChangeFlash(flashSignature)
    }
  }
}

private data class CardSlotCacheKey(
  val card: CardConfig,
  val style: ThemeStyle,
  val dark: Boolean,
  val cardStyle: String,
  val captureEpoch: Long,
  val templateValues: Map<String, String?>,
  val dataSignature: String?,
  val iconSignature: String?,
  val visibilitySignature: String?,
)

/**
 * One emitted dashboard row in the LazyColumn. Either a single full-width card or a cluster of
 * compact cards sharing the row.
 */
private data class DashboardRow(val cards: List<CardConfig>)

/**
 * Group consecutive cards by [CardWidthClass], then chunk Compact groups into rows of [perRow].
 * Authored order is preserved — a Full card between two Compact runs splits them into two clusters.
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
