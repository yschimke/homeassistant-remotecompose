package ee.schimke.terrazzo.dashboard

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.dashboard.CardAddress
import ee.schimke.ha.client.dashboard.DashboardConflictException
import ee.schimke.ha.client.dashboard.DashboardDraft
import ee.schimke.ha.client.dashboard.DashboardEditSession
import ee.schimke.ha.client.dashboard.toCardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.SectionCardGrid
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.liveDashboardSnapshots
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val TAG = "DashboardEditor"

/**
 * The phone's light dashboard editor (docs/architecture/dashboard-editing.md): the dashboard's
 * Lovelace config as a draft, drawn with the same live card previews the dashboard shows, laid out
 * on Home Assistant's section grid ([SectionCardGrid]) so a card's size here is the size it has in
 * HA. Tap a card to select it, then move it, resize it (with the toolbar or by dragging its corner
 * — the cards after it re-flow as it grows), swap its template or delete it; sections move up and
 * down. Nothing reaches Home Assistant until **Save**, which is conditional
 * ([DashboardEditSession]): another client's change since the draft was loaded is refused rather
 * than overwritten.
 *
 * Adding cards, sections, views and dashboards and editing a card's settings belong to Home
 * Assistant's editor or the Terrazzo panel; [IntegrationCard] points there. With the Terrazzo
 * integration installed it also offers **Ask an agent** ([AgentEditScreen]): an edit session in
 * Home Assistant whose drafts are previewed here and applied by an administrator.
 *
 * The dashboard view itself lays sections out by harc's own card designs ([SectionCardFlow]), so it
 * can place cards differently from HA's grid; the editor shows the grid because that is what a
 * card's `grid_options` mean.
 *
 * [saveBlockedReason] says why the draft can't be saved (read-only mode, a demo session); the
 * editor still edits, so a draft can be explored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardEditorScreen(
  session: HaSession,
  urlPath: String?,
  title: String?,
  saveBlockedReason: String?,
  onBack: () -> Unit,
  onSaved: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val snackbars = remember { SnackbarHostState() }
  var load by remember(session, urlPath) { mutableStateOf<EditorLoad>(EditorLoad.Loading) }
  var snapshot by remember(session, urlPath) { mutableStateOf(HaSnapshot()) }
  var integration by remember(session) { mutableStateOf(IntegrationStatus.Unknown) }
  var askingAgent by rememberSaveable(session, urlPath) { mutableStateOf(false) }
  // Bumped when an agent's draft is applied, so the editor reloads what Home Assistant now holds.
  var reloads by remember(session, urlPath) { mutableIntStateOf(0) }
  LaunchedEffect(session) {
    val config = runCatching { session.fetchInstanceConfig() }.getOrNull()
    integration =
      when {
        config == null -> IntegrationStatus.Unknown
        INTEGRATION_DOMAIN in config.components -> IntegrationStatus.Installed
        else -> IntegrationStatus.Missing
      }
  }

  LaunchedEffect(session, urlPath, reloads) {
    load =
      try {
        val stored = session.fetchDashboardConfig(urlPath)
        val config = stored ?: configOf(session.loadDashboard(urlPath).first)
        val reason =
          saveBlockedReason
            ?: if (stored == null) "This session can't save dashboards to Home Assistant." else null
        EditorLoad.Ready(
          DashboardEditSession(config, reason) { expected, replacement ->
            session.compareAndReplaceDashboardConfig(urlPath, expected, replacement)
          }
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Loading $urlPath for editing failed", e)
        EditorLoad.Failed(e.message ?: "Couldn't load this dashboard.")
      }
  }
  // Cards in the editor are live: they keep up with Home Assistant as the dashboard does.
  LaunchedEffect(session, urlPath) {
    val loaded =
      runCatching { session.loadDashboard(urlPath).second }
        .recoverCatching { session.loadSnapshot() }
        .getOrNull() ?: return@LaunchedEffect
    snapshot = loaded
    val interval = session.refreshIntervalMillis
    if (interval != null) {
      while (true) {
        delay(interval)
        runCatching { session.loadDashboard(urlPath).second }.onSuccess { snapshot = it }
      }
    }
    session.liveDashboardSnapshots(loaded).collect { snapshot = it }
  }

  val agentEdits = session.agentEdits
  if (askingAgent && agentEdits != null) {
    val context = LocalContext.current
    BackHandler { askingAgent = false }
    AgentEditScreen(
      edits = agentEdits,
      urlPath = urlPath,
      title = title,
      snapshot = snapshot,
      onBack = { askingAgent = false },
      onSharePrompt = { sharePrompt(context, it) },
      onApplied = {
        reloads++
        onSaved()
      },
    ) { _, card ->
      DashboardCardPreview(session, card, snapshot)
    }
    return
  }

  when (val l = load) {
    EditorLoad.Loading ->
      EditorFrame(title = "Edit dashboard", onBack = onBack, snackbars = snackbars) {
        Text("Loading…", Modifier.padding(24.dp))
      }
    is EditorLoad.Failed ->
      EditorFrame(title = "Edit dashboard", onBack = onBack, snackbars = snackbars) {
        Text(l.message, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
      }
    is EditorLoad.Ready ->
      DashboardEditor(
        session = session,
        edits = l.edits,
        title = title,
        snapshot = snapshot,
        snackbars = snackbars,
        onBack = onBack,
        onSaved = onSaved,
        launch = { block -> scope.launch { block() } },
        integration = integration,
        urlPath = urlPath,
        onAskAgent = agentEdits?.let { { askingAgent = true } },
      )
  }
}

/** The Terrazzo integration's domain (`custom_components/terrazzo`). */
private const val INTEGRATION_DOMAIN = "terrazzo"

private sealed interface EditorLoad {
  data object Loading : EditorLoad

  data class Failed(val message: String) : EditorLoad

  class Ready(val edits: DashboardEditSession) : EditorLoad
}

private val editorJson = Json {
  prettyPrint = true
  encodeDefaults = false
  explicitNulls = false
}

/** A config for a session that serves dashboards without their stored config (demo mode). */
internal fun configOf(dashboard: Dashboard): JsonObject =
  editorJson.encodeToJsonElement(Dashboard.serializer(), dashboard) as JsonObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardEditor(
  session: HaSession,
  edits: DashboardEditSession,
  title: String?,
  snapshot: HaSnapshot,
  snackbars: SnackbarHostState,
  onBack: () -> Unit,
  onSaved: () -> Unit,
  launch: (suspend () -> Unit) -> Unit,
  initialSelection: CardAddress? = null,
  integration: IntegrationStatus = IntegrationStatus.Unknown,
  urlPath: String? = null,
  onAskAgent: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val editInHomeAssistant =
    homeAssistantEditUrl(session.baseUrl, urlPath)?.let { url -> { openUrl(context, url) } }
  var draft by remember(edits) { mutableStateOf(edits.draft) }
  var saved by remember(edits) { mutableStateOf(edits.draft) }
  val history = remember(edits) { mutableStateListOf<JsonObject>() }
  var viewIndex by remember(edits) { mutableIntStateOf(0) }
  var selected by remember(edits) { mutableStateOf(initialSelection) }
  var confirmDiscard by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }

  /** Apply an edit to the draft, keeping the previous draft for Undo. */
  fun apply(next: JsonObject) {
    if (next == draft) return
    history += draft
    if (history.size > 50) history.removeAt(0)
    draft = next
    edits.edit(next)
  }

  fun attempt(edit: () -> Unit) {
    runCatching(edit).onFailure {
      launch { snackbars.showSnackbar(it.message ?: "Can't do that.") }
    }
  }

  val views = DashboardDraft.views(draft)
  viewIndex = viewIndex.coerceIn(0, (views.size - 1).coerceAtLeast(0))
  val dirty = draft != saved
  val leave = { if (dirty) confirmDiscard = true else onBack() }
  BackHandler {
    when {
      selected != null -> selected = null
      else -> leave()
    }
  }

  fun save() {
    busy = true
    launch {
      val result = runCatching { edits.save() }
      busy = false
      result
        .onSuccess {
          // What HA now holds; edits made while the save was in flight stay unsaved.
          saved = edits.baseline
          onSaved()
          val undo = snackbars.showSnackbar("Saved to Home Assistant.", actionLabel = "Undo")
          if (undo == SnackbarResult.ActionPerformed) {
            runCatching { edits.undoSave() }
              .onSuccess {
                saved = edits.baseline
                draft = edits.draft
                onSaved()
              }
              .onFailure { snackbars.showSnackbar(it.message ?: "Couldn't undo.") }
          }
        }
        .onFailure {
          val message =
            if (it is DashboardConflictException) it.message.orEmpty()
            else it.message ?: "Home Assistant refused the dashboard."
          snackbars.showSnackbar(message)
        }
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            "Edit ${title ?: "dashboard"}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        navigationIcon = {
          IconButton(onClick = leave) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(
            enabled = history.isNotEmpty(),
            onClick = {
              draft = history.removeAt(history.lastIndex)
              edits.edit(draft)
              selected = null
            },
          ) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo edit")
          }
          TextButton(
            enabled = dirty && !busy && edits.readOnlyReason == null,
            onClick = ::save,
          ) {
            Text("Save")
          }
        },
      )
    },
    bottomBar = {
      val at = selected
      val card = at?.let { DashboardDraft.card(draft, it) }
      if (at != null && card != null) {
        SelectedCardBar(
          card = card,
          canMoveToSection = at.section != null,
          sectionCount = DashboardDraft.sections(draft, at.view).size,
          onMove = { by ->
            attempt {
              val (next, to) = DashboardDraft.moveCard(draft, at, by)
              apply(next)
              selected = to
            }
          },
          onMoveToSection = { section ->
            attempt {
              val (next, to) = DashboardDraft.moveCardToSection(draft, at, section)
              apply(next)
              selected = to
            }
          },
          onResize = { columns, rows ->
            attempt { apply(DashboardDraft.resizeCard(draft, at, columns, rows)) }
          },
          templates = DashboardDraft.templateChoices(card),
          onTemplate = { type ->
            attempt {
              apply(DashboardDraft.replaceCard(draft, at, DashboardDraft.withType(card, type)))
            }
          },
          onDelete = {
            attempt {
              apply(DashboardDraft.removeCard(draft, at))
              selected = null
            }
          },
          onDone = { selected = null },
        )
      }
    },
    snackbarHost = { SnackbarHost(snackbars) },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
      edits.readOnlyReason?.let {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
          Text(
            "$it You can still try edits here.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          )
        }
      }
      if (views.size > 1) {
        PrimaryScrollableTabRow(selectedTabIndex = viewIndex, edgePadding = 16.dp) {
          views.forEachIndexed { i, view ->
            Tab(
              selected = i == viewIndex,
              onClick = {
                viewIndex = i
                selected = null
              },
              text = { Text(view.stringOf("title") ?: "View ${i + 1}") },
            )
          }
        }
      }
      if (views.isEmpty()) {
        Text("This dashboard has no views.", Modifier.padding(24.dp))
      } else {
        EditorCanvas(
          session = session,
          draft = draft,
          view = viewIndex,
          snapshot = snapshot,
          selected = selected,
          onSelect = { selected = it },
          onResize = { at, columns, rows ->
            // A drag resizes continuously; one undo step per drag is kept by onResizeStart.
            val next = DashboardDraft.resizeCard(draft, at, columns, rows)
            draft = next
            edits.edit(next)
          },
          onResizeStart = { history += draft },
          footer = {
            IntegrationCard(
              integration,
              editInHomeAssistant,
              onAskAgent = onAskAgent?.takeIf { integration == IntegrationStatus.Installed },
            ) {
              openUrl(context, HACS_INSTALL_URL)
            }
          },
          onMoveSection = { section, by ->
            attempt { apply(DashboardDraft.moveSection(draft, viewIndex, section, by)) }
            selected = null
          },
        )
      }
    }
  }

  if (confirmDiscard) {
    AlertDialog(
      onDismissRequest = { confirmDiscard = false },
      title = { Text("Discard changes?") },
      text = { Text("Your edits to this dashboard haven't been saved to Home Assistant.") },
      confirmButton = {
        TextButton(
          onClick = {
            confirmDiscard = false
            onBack()
          }
        ) {
          Text("Discard")
        }
      },
      dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
    )
  }
}

@Composable
private fun EditorFrame(
  title: String,
  onBack: () -> Unit,
  snackbars: SnackbarHostState,
  content: @Composable () -> Unit,
) {
  @OptIn(ExperimentalMaterial3Api::class)
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(title) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbars) },
  ) { padding ->
    Box(Modifier.padding(padding)) { content() }
  }
}

/** One view of the draft: its sections (or a masonry view's cards), each an editable card grid. */
@Composable
private fun EditorCanvas(
  session: HaSession,
  draft: JsonObject,
  view: Int,
  snapshot: HaSnapshot,
  selected: CardAddress?,
  onSelect: (CardAddress?) -> Unit,
  onResize: (CardAddress, Int, Int?) -> Unit,
  onResizeStart: () -> Unit,
  onMoveSection: (section: Int, by: Int) -> Unit,
  footer: @Composable () -> Unit,
) {
  val sectioned = DashboardDraft.isSections(draft, view)
  val sections: List<Int?> =
    if (sectioned) DashboardDraft.sections(draft, view).indices.toList() else listOf(null)
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    itemsIndexed(sections, key = { i, _ -> "section-$view-$i" }) { i, section ->
      Column(
        Modifier.fillMaxWidth().widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (section != null) {
          SectionHeader(
            index = section,
            count = sections.size,
            onMove = { by -> onMoveSection(section, by) },
          )
        }
        val cards = DashboardDraft.cards(draft, view, section)
        if (cards.isEmpty()) {
          Text(
            "No cards yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        EditableCardGrid(
          cards = cards,
          selectedIndex = selected?.takeIf { it.view == view && it.section == section }?.card,
          onSelect = { card -> onSelect(CardAddress(view, section, card)) },
          onResizeStart = onResizeStart,
          onResize = { card, columns, rows ->
            onResize(CardAddress(view, section, card), columns, rows)
          },
        ) { card ->
          DashboardCardPreview(session, card.toCardConfig(), snapshot)
        }
      }
      if (i < sections.lastIndex) HorizontalDivider(Modifier.padding(top = 8.dp))
    }
    item(key = "integration") { footer() }
  }
}

@Composable
private fun SectionHeader(index: Int, count: Int, onMove: (Int) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      "Section ${index + 1}",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f),
    )
    IconButton(enabled = index > 0, onClick = { onMove(-1) }) {
      Icon(Icons.Filled.ArrowUpward, contentDescription = "Move section ${index + 1} up")
    }
    IconButton(enabled = index < count - 1, onClick = { onMove(1) }) {
      Icon(Icons.Filled.ArrowDownward, contentDescription = "Move section ${index + 1} down")
    }
  }
}

/**
 * One section's [cards] on HA's 12-column section grid ([SectionCardGrid.plan]): each card spans
 * its `grid_options` columns, fixed-row cards are as tall as their rows, and the cards after a
 * resized one re-flow into the space it leaves or takes. Taps select a card instead of acting on
 * it; the selected card carries a corner handle that resizes it by dragging, in whole columns and
 * rows.
 */
@Composable
private fun EditableCardGrid(
  cards: List<JsonObject>,
  selectedIndex: Int?,
  onSelect: (Int) -> Unit,
  onResizeStart: () -> Unit,
  onResize: (index: Int, columns: Int, rows: Int?) -> Unit,
  preview: @Composable (JsonObject) -> Unit,
) {
  val plan = remember(cards) { SectionCardGrid.plan(cards.map { it.toCardConfig() }) }
  val gap = SectionCardGrid.COLUMN_GAP_DP.dp
  val rowGap = SectionCardGrid.ROW_GAP_DP.dp
  val latestCards by rememberUpdatedState(cards)
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val columnWidth = (maxWidth - gap * (plan.columnCount - 1)) / plan.columnCount
    Layout(
      content = {
        cards.forEachIndexed { i, card ->
          // Keyed by position, not content: a resize changes the card, and a slot recreated
          // mid-drag would cancel the drag. The preview alone follows the card config.
          key(i) {
            val isSelected = i == selectedIndex
            Box(
              Modifier.then(
                if (isSelected)
                  Modifier.border(
                    BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                    RoundedCornerShape(14.dp),
                  )
                else Modifier
              )
            ) {
              key(card) { preview(card) }
              // Over the player, so a tap selects the card rather than toggling its entity.
              // It carries the card's label, so a screen reader announces and selects the card.
              Box(
                Modifier.matchParentSize()
                  .semantics {
                    contentDescription = "Card ${i + 1}: ${card.stringOf("type")}"
                    selected = isSelected
                  }
                  .clickable(onClickLabel = "Select") { onSelect(i) }
              )
              if (isSelected) {
                ResizeHandle(
                  columnStep = columnWidth + gap,
                  rowStep = (SectionCardGrid.ROW_HEIGHT_DP + SectionCardGrid.ROW_GAP_DP).dp,
                  modifier = Modifier.align(Alignment.BottomEnd),
                  onStart = onResizeStart,
                  onDrag = { dColumns, dRows, start ->
                    val size = DashboardDraft.size(latestCards[i])
                    val columns =
                      (start.columns + dColumns).coerceIn(size.minColumns, size.maxColumns)
                    // Dragging up past a card's first row returns it to its natural height.
                    val rows =
                      start.rows?.let { it + dRows }?.takeIf { it >= 1 }
                        ?: if (dRows > 0) dRows.coerceAtLeast(1) else null
                    if (columns != size.columns || rows != size.rows) onResize(i, columns, rows)
                  },
                  currentSize = { DashboardDraft.size(latestCards[i]) },
                )
              }
            }
          }
        }
      }
    ) { measurables, constraints ->
      val width = constraints.maxWidth
      val gapPx = gap.roundToPx()
      val rowGapPx = rowGap.roundToPx()
      val column =
        (width - gapPx * (plan.columnCount - 1)).coerceAtLeast(0) / plan.columnCount.toFloat()
      val byIndex = plan.placements.associateBy { it.index }
      val placeables = measurables.mapIndexed { i, m ->
        val p = byIndex.getValue(i)
        val w =
          (column * p.columnSpan + gapPx * (p.columnSpan - 1)).roundToInt().coerceAtMost(width)
        val fixed = p.fixedHeightDp?.dp?.roundToPx()
        m.measure(
          if (fixed != null) Constraints.fixed(w, fixed)
          else Constraints(minWidth = w, maxWidth = w)
        )
      }
      val rows = IntArray(plan.rowCount)
      plan.placements.forEach { p ->
        if (p.rowSpan == 1) rows[p.row] = maxOf(rows[p.row], placeables[p.index].height)
      }
      // A card spanning several rows grows its last row if the rows it spans are too short.
      plan.placements
        .filter { it.rowSpan > 1 }
        .forEach { p ->
          val spanned =
            (p.row until p.row + p.rowSpan).sumOf { rows[it] } + rowGapPx * (p.rowSpan - 1)
          val deficit = placeables[p.index].height - spanned
          if (deficit > 0) rows[p.row + p.rowSpan - 1] += deficit
        }
      val tops = IntArray(rows.size)
      for (r in 1 until rows.size) tops[r] = tops[r - 1] + rows[r - 1] + rowGapPx
      val height = if (rows.isEmpty()) 0 else tops.last() + rows.last()
      layout(width, height) {
        plan.placements.forEach { p ->
          val x = (p.column * (column + gapPx)).roundToInt()
          placeables[p.index].place(x, tops[p.row])
        }
      }
    }
  }
}

/**
 * The selected card's corner handle: dragging it right or left adds or removes a column per
 * [columnStep], down or up a fixed row per [rowStep], measured from the size the drag started at.
 */
@Composable
private fun ResizeHandle(
  columnStep: Dp,
  rowStep: Dp,
  currentSize: () -> ee.schimke.ha.client.dashboard.CardSize,
  onStart: () -> Unit,
  onDrag: (dColumns: Int, dRows: Int, start: ee.schimke.ha.client.dashboard.CardSize) -> Unit,
  modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current
  // The gesture outlives recompositions (the card it resizes moves, its size changes): read the
  // latest callbacks rather than the ones it started with.
  val latestSize by rememberUpdatedState(currentSize)
  val latestStart by rememberUpdatedState(onStart)
  val latestDrag by rememberUpdatedState(onDrag)
  val columnPx = with(density) { columnStep.toPx() }.coerceAtLeast(1f)
  val rowPx = with(density) { rowStep.toPx() }
  Box(
    modifier
      .padding(4.dp)
      .size(36.dp)
      .background(MaterialTheme.colorScheme.primary, CircleShape)
      .semantics { contentDescription = "Drag to resize" }
      .pointerInput(columnPx, rowPx) {
        var start = latestSize()
        var dx = 0f
        var dy = 0f
        detectDragGestures(
          onDragStart = {
            start = latestSize()
            dx = 0f
            dy = 0f
            latestStart()
          }
        ) { change, amount ->
          change.consume()
          dx += amount.x
          dy += amount.y
          latestDrag((dx / columnPx).roundToInt(), (dy / rowPx).roundToInt(), start)
        }
      },
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      Icons.Filled.OpenInFull,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onPrimary,
      modifier = Modifier.size(20.dp),
    )
  }
}

/** The selected card's tools: order, width, height, template, delete. */
@Composable
private fun SelectedCardBar(
  card: JsonObject,
  canMoveToSection: Boolean,
  sectionCount: Int,
  onMove: (Int) -> Unit,
  onMoveToSection: (Int) -> Unit,
  onResize: (columns: Int, rows: Int?) -> Unit,
  templates: List<String>,
  onTemplate: (String) -> Unit,
  onDelete: () -> Unit,
  onDone: () -> Unit,
) {
  val size = DashboardDraft.size(card)
  var sectionMenu by remember { mutableStateOf(false) }
  var templateMenu by remember { mutableStateOf(false) }
  Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp)) {
      // Size: width in quarters of the section, height in fixed rows or the card's own.
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
          enabled = size.columns > size.minColumns,
          onClick = { onResize(DashboardDraft.stepColumns(size.columns, -1), size.rows) },
        ) {
          Icon(Icons.Filled.Remove, contentDescription = "Narrower")
        }
        Text(
          "${size.columns}/12 wide",
          style = MaterialTheme.typography.labelLarge,
          modifier = Modifier.semantics { contentDescription = "${size.columns} of 12 columns" },
        )
        IconButton(
          enabled = size.columns < size.maxColumns,
          onClick = { onResize(DashboardDraft.stepColumns(size.columns, 1), size.rows) },
        ) {
          Icon(Icons.Filled.Add, contentDescription = "Wider")
        }
        Spacer(Modifier.weight(1f))
        IconButton(
          enabled = size.rows != null,
          onClick = {
            val rows = size.rows
            onResize(size.columns, if (rows == null || rows <= size.minRows) null else rows - 1)
          },
        ) {
          Icon(Icons.Filled.UnfoldLess, contentDescription = "Shorter")
        }
        Text(
          when (val rows = size.rows) {
            null -> "auto height"
            1 -> "1 row"
            else -> "$rows rows"
          },
          style = MaterialTheme.typography.labelLarge,
        )
        IconButton(
          enabled = (size.rows ?: 0) < size.maxRows,
          onClick = { onResize(size.columns, (size.rows ?: 0) + 1) },
        ) {
          Icon(Icons.Filled.UnfoldMore, contentDescription = "Taller")
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "Done") }
        IconButton(onClick = { onMove(-1) }) {
          Icon(Icons.Filled.ArrowUpward, contentDescription = "Move earlier")
        }
        IconButton(onClick = { onMove(1) }) {
          Icon(Icons.Filled.ArrowDownward, contentDescription = "Move later")
        }
        if (canMoveToSection && sectionCount > 1) {
          Box {
            TextButton(onClick = { sectionMenu = true }) { Text("Section…") }
            DropdownMenu(expanded = sectionMenu, onDismissRequest = { sectionMenu = false }) {
              repeat(sectionCount) { s ->
                DropdownMenuItem(
                  text = { Text("Move to section ${s + 1}") },
                  onClick = {
                    sectionMenu = false
                    onMoveToSection(s)
                  },
                )
              }
            }
          }
        }
        Spacer(Modifier.weight(1f))
        if (templates.isNotEmpty()) {
          Box {
            TextButton(onClick = { templateMenu = true }) { Text("Template…") }
            DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
              templates.forEach { type ->
                DropdownMenuItem(
                  text = { Text(templateLabel(type)) },
                  onClick = {
                    templateMenu = false
                    onTemplate(type)
                  },
                )
              }
            }
          }
        }
        IconButton(onClick = onDelete) {
          Icon(Icons.Filled.Delete, contentDescription = "Delete card")
        }
      }
    }
  }
}

private fun JsonObject.stringOf(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** A card type as the template menu names it: "history-graph" reads "History graph". */
private fun templateLabel(type: String): String =
  type.replace('-', ' ').replaceFirstChar { it.uppercase() }

/** Whether this Home Assistant has the Terrazzo integration loaded, as `get_config` reports it. */
enum class IntegrationStatus {
  Installed,
  Missing,
  /** No instance config to ask (demo mode, offline). */
  Unknown,
}

/**
 * Where the editing the phone doesn't do lives: Home Assistant's own dashboard editor for adding
 * cards, sections and dashboards, an agent through the Terrazzo integration ([onAskAgent], when it
 * is installed), and — when it isn't — the integration from HACS.
 */
@Composable
internal fun IntegrationCard(
  status: IntegrationStatus,
  onOpenHomeAssistant: (() -> Unit)?,
  onAskAgent: (() -> Unit)? = null,
  onInstall: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Need more than moving and resizing?", style = MaterialTheme.typography.titleSmall)
      Text(
        "Add cards and sections, change a card's settings or create dashboards in Home " +
          "Assistant's own editor.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      onOpenHomeAssistant?.let { open ->
        OutlinedButton(onClick = open) { Text("Open in Home Assistant") }
      }
      onAskAgent?.let { ask ->
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(
          "Or describe the change and let your AI assistant draft it. You preview each draft " +
            "here before it reaches Home Assistant.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = ask) { Text("Ask an agent") }
      }
      if (status != IntegrationStatus.Installed) {
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(
          "The Terrazzo integration, from HACS, shows your dashboards in Home Assistant as harc " +
            "draws them, and lets AI assistants read your home.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = onInstall) { Text("Install with HACS") }
      }
    }
  }
}

/** HACS's page for this repository, through My Home Assistant (docs/HOME_ASSISTANT.md). */
internal const val HACS_INSTALL_URL =
  "https://my.home-assistant.io/redirect/hacs_repository/" +
    "?owner=yschimke&repository=homeassistant-remotecompose&category=integration"

/** [urlPath]'s dashboard in Home Assistant's frontend, opened in edit mode. */
internal fun homeAssistantEditUrl(baseUrl: String?, urlPath: String?): String? =
  baseUrl
    ?.trimEnd('/')
    ?.takeIf { it.startsWith("http") }
    ?.let { "$it/${urlPath ?: "lovelace"}/0?edit=1" }

/** The share sheet for an edit session's prompt: it names the session, never a token. */
private fun sharePrompt(context: Context, prompt: String) {
  val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, prompt)
  runCatching {
    context.startActivity(
      Intent.createChooser(send, "Share prompt").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
  }
    .onFailure { Log.w(TAG, "Nothing shares the prompt", it) }
}

private fun openUrl(context: Context, url: String) {
  runCatching {
    context.startActivity(
      Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
  }
    .onFailure { Log.w(TAG, "Nothing opens $url", it) }
}
