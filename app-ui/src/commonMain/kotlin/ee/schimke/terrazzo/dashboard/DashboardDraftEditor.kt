package ee.schimke.terrazzo.dashboard

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.dashboard.CardAddress
import ee.schimke.ha.client.dashboard.DashboardConflictException
import ee.schimke.ha.client.dashboard.DashboardDraft
import ee.schimke.ha.client.dashboard.DashboardEditSession
import ee.schimke.ha.client.dashboard.toCardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.SectionCardGrid
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Which edits [DashboardDraftEditor] offers (docs/architecture/dashboard-editing.md). The phone
 * keeps to [Light]: move, resize, swap a card's template, delete, reorder sections. The Terrazzo
 * panel and the desktop app offer [Full]: also add cards, sections and views, rename and delete
 * views and sections, and edit a card's settings as JSON.
 */
enum class EditorScope {
  Light,
  Full,
}

/**
 * A dashboard's Lovelace config as a draft, drawn with the host's live card previews ([renderCard])
 * on Home Assistant's section grid ([SectionCardGrid]), so a card's size here is the size it has in
 * HA. Tap a card to select it, then move it, resize it (with the toolbar or by dragging its corner
 * — the cards after it re-flow as it grows), swap its template or delete it; sections move up and
 * down. Nothing reaches Home Assistant until **Save**, which is conditional
 * ([DashboardEditSession]): another client's change since the draft was loaded is refused rather
 * than overwritten. One saved change can be undone.
 *
 * [backHandler] hooks the platform's back gesture (none on the web); [footer] ends the canvas and
 * is told whether the draft has unsaved edits, with a way to drop them. [entities] are the home's
 * entities, for [EditorScope.Full]'s add-card picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardDraftEditor(
  edits: DashboardEditSession,
  title: String?,
  snackbars: SnackbarHostState,
  onBack: () -> Unit,
  onSaved: () -> Unit,
  launch: (suspend () -> Unit) -> Unit,
  scope: EditorScope = EditorScope.Light,
  entities: Collection<EntityState> = emptyList(),
  initialSelection: CardAddress? = null,
  backHandler: @Composable (onBack: () -> Unit) -> Unit = {},
  footer: @Composable (dirty: Boolean, discardEdits: () -> Unit) -> Unit = { _, _ -> },
  renderCard: @Composable (JsonObject) -> Unit,
) {
  val full = scope == EditorScope.Full
  var draft by remember(edits) { mutableStateOf(edits.draft) }
  var saved by remember(edits) { mutableStateOf(edits.draft) }
  val history = remember(edits) { mutableStateListOf<JsonObject>() }
  var viewIndex by remember(edits) { mutableIntStateOf(0) }
  var selected by remember(edits) { mutableStateOf(initialSelection) }
  var confirmDiscard by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  // Full scope's dialogs.
  var addingTo by remember { mutableStateOf<AddTarget?>(null) }
  var editingCard by remember { mutableStateOf<CardAddress?>(null) }
  var viewTitle by remember { mutableStateOf<ViewTitleEdit?>(null) }
  var viewMenu by remember { mutableStateOf(false) }

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
  backHandler {
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
          Text("Edit ${title ?: "dashboard"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
          if (full) {
            Box {
              IconButton(onClick = { viewMenu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "View options")
              }
              DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                DropdownMenuItem(
                  text = { Text("Add view") },
                  onClick = {
                    viewMenu = false
                    viewTitle = ViewTitleEdit(view = null, title = "")
                  },
                )
                if (views.isNotEmpty()) {
                  DropdownMenuItem(
                    text = { Text("Rename view") },
                    onClick = {
                      viewMenu = false
                      viewTitle =
                        ViewTitleEdit(viewIndex, views[viewIndex].stringOf("title").orEmpty())
                    },
                  )
                }
                if (views.size > 1) {
                  DropdownMenuItem(
                    text = { Text("Delete view") },
                    onClick = {
                      viewMenu = false
                      attempt {
                        apply(DashboardDraft.removeView(draft, viewIndex))
                        selected = null
                      }
                    },
                  )
                }
              }
            }
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
          onSettings = if (full) ({ editingCard = at }) else null,
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
          draft = draft,
          view = viewIndex,
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
            footer(dirty) {
              edits.edit(saved)
              draft = saved
              history.clear()
              selected = null
            }
          },
          onMoveSection = { section, by ->
            attempt { apply(DashboardDraft.moveSection(draft, viewIndex, section, by)) }
            selected = null
          },
          renderCard = renderCard,
          onAddCard = if (full) ({ section -> addingTo = AddTarget(viewIndex, section) }) else null,
          onAddSection =
            if (full) ({ attempt { apply(DashboardDraft.addSection(draft, viewIndex, null)) } })
            else null,
          onRemoveSection =
            if (full)
              ({ section ->
                attempt {
                  apply(DashboardDraft.removeSection(draft, viewIndex, section))
                  selected = null
                }
              })
            else null,
        )
      }
    }
  }

  addingTo?.let { target ->
    AddCardDialog(
      entities = entities,
      onDismiss = { addingTo = null },
      onAdd = { card ->
        addingTo = null
        attempt {
          val (next, at) = DashboardDraft.addCard(draft, target.view, target.section, card)
          apply(next)
          selected = at
        }
      },
    )
  }

  editingCard?.let { at ->
    val card = DashboardDraft.card(draft, at)
    if (card == null) editingCard = null
    else
      CardSettingsDialog(
        card = card,
        onDismiss = { editingCard = null },
        onSave = { replacement ->
          editingCard = null
          attempt { apply(DashboardDraft.replaceCard(draft, at, replacement)) }
        },
      )
  }

  viewTitle?.let { edit ->
    ViewTitleDialog(
      edit = edit,
      onDismiss = { viewTitle = null },
      onSave = { name ->
        viewTitle = null
        attempt {
          val view = edit.view
          if (view == null) {
            apply(DashboardDraft.addView(draft, name))
            viewIndex = DashboardDraft.views(draft).lastIndex
          } else apply(DashboardDraft.renameView(draft, view, name))
          selected = null
        }
      },
    )
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

/** Where a new card goes: a view, and its section (none for a masonry view). */
private data class AddTarget(val view: Int, val section: Int?)

/** Naming a new view ([view] null) or renaming one. */
private data class ViewTitleEdit(val view: Int?, val title: String)

/** The entities the add-card picker lists for [query]: every word in the name or id. */
internal fun entityMatches(
  entities: Collection<EntityState>,
  query: String,
  limit: Int = 50,
): List<EntityState> {
  val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
  fun name(state: EntityState) =
    (state.attributes["friendly_name"] as? JsonPrimitive)?.contentOrNull ?: state.entityId
  return entities
    .filter { state ->
      val haystack = "${name(state)} ${state.entityId}".lowercase()
      words.all { it in haystack }
    }
    .sortedBy { name(it).lowercase() }
    .take(limit)
}

/**
 * Add a card: pick an entity (searched by name or id) and then one of the types that suit it
 * ([DashboardDraft.suggestedTypes]), or a card without an entity (a heading, text, a clock).
 */
@Composable
private fun AddCardDialog(
  entities: Collection<EntityState>,
  onDismiss: () -> Unit,
  onAdd: (JsonObject) -> Unit,
) {
  var query by remember { mutableStateOf("") }
  var entity by remember { mutableStateOf<EntityState?>(null) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(entity?.let { "Card for ${it.entityId}" } ?: "Add card") },
    text = {
      val picked = entity
      if (picked == null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search entities") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(DashboardDraft.ENTITYLESS_TYPES) { type ->
              TextButton(onClick = { onAdd(DashboardDraft.entitylessCard(type)) }) {
                Text(templateLabel(type))
              }
            }
            items(entityMatches(entities, query), key = { it.entityId }) { state ->
              TextButton(
                onClick = { entity = state },
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                  ((state.attributes["friendly_name"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.let { "$it · " }
                    .orEmpty()) + state.entityId,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.fillMaxWidth(),
                )
              }
            }
          }
        }
      } else {
        Column {
          DashboardDraft.suggestedTypes(picked.entityId).forEach { type ->
            TextButton(onClick = { onAdd(DashboardDraft.cardFor(picked.entityId, type)) }) {
              Text(templateLabel(type))
            }
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(onClick = { if (entity != null) entity = null else onDismiss() }) {
        Text(if (entity != null) "Back" else "Cancel")
      }
    },
  )
}

private val settingsJson = Json { prettyPrint = true }

/**
 * A card's settings as its Lovelace JSON: anything a card type takes, without a form per type. The
 * edit must stay one JSON object with a `type`.
 */
@Composable
private fun CardSettingsDialog(
  card: JsonObject,
  onDismiss: () -> Unit,
  onSave: (JsonObject) -> Unit,
) {
  var text by
    remember(card) { mutableStateOf(settingsJson.encodeToString(JsonObject.serializer(), card)) }
  val parsed = remember(text) { parseCardSettings(text) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Card settings") },
    text = {
      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        isError = parsed.isFailure,
        supportingText = { parsed.exceptionOrNull()?.let { Text(it.message.orEmpty()) } },
        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp),
      )
    },
    confirmButton = {
      TextButton(enabled = parsed.isSuccess, onClick = { parsed.onSuccess(onSave) }) {
        Text("Apply")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/** [text] as a card config: one JSON object with a `type`, or why not. */
internal fun parseCardSettings(text: String): Result<JsonObject> = runCatching {
  val element = runCatching {
    Json.parseToJsonElement(text)
  }
    .getOrElse { throw IllegalArgumentException("Not valid JSON.") }
  val card = element as? JsonObject ?: throw IllegalArgumentException("A card is a JSON object.")
  require((card["type"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true) {
    "A card needs a \"type\"."
  }
  card
}

@Composable
private fun ViewTitleDialog(edit: ViewTitleEdit, onDismiss: () -> Unit, onSave: (String) -> Unit) {
  var name by remember(edit) { mutableStateOf(edit.title) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (edit.view == null) "Add view" else "Rename view") },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Title") },
        singleLine = true,
      )
    },
    confirmButton = {
      TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) {
        Text(if (edit.view == null) "Add" else "Rename")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/** One view of the draft: its sections (or a masonry view's cards), each an editable card grid. */
@Composable
private fun EditorCanvas(
  draft: JsonObject,
  view: Int,
  selected: CardAddress?,
  onSelect: (CardAddress?) -> Unit,
  onResize: (CardAddress, Int, Int?) -> Unit,
  onResizeStart: () -> Unit,
  onMoveSection: (section: Int, by: Int) -> Unit,
  footer: @Composable () -> Unit,
  renderCard: @Composable (JsonObject) -> Unit,
  // Full scope only: adding cards and sections, removing sections.
  onAddCard: ((section: Int?) -> Unit)? = null,
  onAddSection: (() -> Unit)? = null,
  onRemoveSection: ((section: Int) -> Unit)? = null,
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
            onRemove = onRemoveSection?.let { remove -> { remove(section) } },
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
          preview = renderCard,
        )
        onAddCard?.let { add ->
          TextButton(onClick = { add(section) }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(if (section == null) "Add card" else "Add card to section ${section + 1}")
          }
        }
      }
      if (i < sections.lastIndex) HorizontalDivider(Modifier.padding(top = 8.dp))
    }
    if (sectioned && onAddSection != null) {
      item(key = "add-section") {
        OutlinedButton(onClick = onAddSection) {
          Icon(Icons.Filled.Add, contentDescription = null)
          Text("Add section")
        }
      }
    }
    item(key = "footer") { footer() }
  }
}

@Composable
private fun SectionHeader(
  index: Int,
  count: Int,
  onMove: (Int) -> Unit,
  onRemove: (() -> Unit)? = null,
) {
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
    onRemove?.let { remove ->
      IconButton(onClick = remove) {
        Icon(Icons.Filled.Delete, contentDescription = "Delete section ${index + 1}")
      }
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
  onSettings: (() -> Unit)? = null,
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
        onSettings?.let { TextButton(onClick = it) { Text("Settings…") } }
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
