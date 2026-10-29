package ee.schimke.terrazzo.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.room.CurrentRoom
import ee.schimke.ha.room.RoomEntity
import kotlinx.serialization.json.JsonPrimitive

/**
 * What every room layout draws from — the flat screen here, an XR platform's spatial panels — and
 * the one part each host supplies: [entityCard], an entity's own controls (the phone app draws its
 * more-info controls in the user's card style; another host draws its own).
 */
class RoomContent(
  val state: RoomState?,
  val snapshot: HaSnapshot?,
  val tracker: String?,
  val onPick: (String?) -> Unit,
  val onFollow: (String?) -> Unit,
  val entityCard: @Composable (RoomEntity, Modifier) -> Unit,
)

/**
 * The room as a flat screen: a top bar with the room's name, its tracker menu and the host's
 * [actions] (an XR host's way into full space), then the header and a grid of the room's controls,
 * the dashboards' first and the rest under "More in".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlatRoomControls(
  content: RoomContent,
  onBack: () -> Unit,
  actions: @Composable RowScope.() -> Unit = {},
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(content.state?.controls?.area?.name ?: "This room") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          RoomTrackerMenu(content)
          actions()
        },
      )
    }
  ) { padding ->
    val state = content.state
    if (state == null) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
      return@Scaffold
    }
    LazyVerticalGrid(
      columns = GridCells.Adaptive(320.dp),
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(16.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item(span = { GridItemSpan(maxLineSpan) }) { RoomHeader(content) }
      val controls = state.controls
      if (controls == null || controls.isEmpty) {
        item(span = { GridItemSpan(maxLineSpan) }) { EmptyRoom(state) }
        return@LazyVerticalGrid
      }
      items(controls.primary, key = { it.entityId }) { content.entityCard(it, Modifier) }
      if (controls.more.isNotEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
          Text(
            "More in ${controls.area.name}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
        items(controls.more, key = { it.entityId }) { content.entityCard(it, Modifier) }
      }
    }
  }
}

/** Where the room came from, and the other rooms to switch to: automatic first. */
@Composable
fun RoomHeader(content: RoomContent, modifier: Modifier = Modifier) {
  val state = content.state ?: return
  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      sourceLabel(state),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val showing = state.controls?.area?.id
    val automatic = state.current.source != CurrentRoom.Source.Picked
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      item {
        FilterChip(
          selected = automatic,
          onClick = { content.onPick(null) },
          label = { Text("Automatic") },
          leadingIcon = { Icon(Icons.Filled.MyLocation, null, Modifier.size(18.dp)) },
        )
      }
      items(state.rooms, key = { it.id }) { room ->
        FilterChip(
          selected = !automatic && room.id == showing,
          onClick = { content.onPick(room.id) },
          label = { Text(room.name) },
        )
      }
    }
  }
}

/** Picks the entity that follows the user between rooms, from those naming a room now. */
@Composable
fun RoomTrackerMenu(content: RoomContent) {
  val candidates = content.state?.trackerCandidates.orEmpty()
  if (candidates.isEmpty() && content.tracker == null) return
  var open by remember { mutableStateOf(false) }
  IconButton(onClick = { open = true }) {
    Icon(Icons.Filled.MyLocation, contentDescription = "Room tracker")
  }
  DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
    DropdownMenuItem(
      text = { Text("No room tracker") },
      onClick = {
        open = false
        content.onFollow(null)
      },
    )
    (listOfNotNull(content.tracker) + candidates).distinct().forEach { id ->
      val name =
        (content.snapshot?.states?.get(id)?.attributes?.get("friendly_name") as? JsonPrimitive)
          ?.takeIf { it.isString }
          ?.content ?: id
      DropdownMenuItem(
        text = { Text(if (id == content.tracker) "✓ $name" else name) },
        onClick = {
          open = false
          content.onFollow(id)
        },
      )
    }
  }
}

@Composable
private fun EmptyRoom(state: RoomState) {
  val message =
    when {
      state.rooms.isEmpty() ->
        "No rooms with controls yet. Assign devices to areas in Home Assistant and they'll show here."
      else -> "Nothing to control in ${state.current.area?.name ?: "this room"}."
    }
  Text(message, style = MaterialTheme.typography.bodyLarge)
}

/**
 * A control panel's "Pin here": pins the entity's controls where the user looks, onto the wall
 * there if one is in reach.
 */
@Composable
fun PinHereButton(onClick: () -> Unit) {
  TextButton(onClick = onClick) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(Icons.Filled.PushPin, contentDescription = null)
      Text("Pin here")
    }
  }
}

/** The pins whose place the headset couldn't load, each with a way to remove it. */
@Composable
fun LostPins(lost: List<RoomPin>, snapshot: HaSnapshot?, onRemove: (RoomPin) -> Unit) {
  if (lost.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("Pins not found here", style = MaterialTheme.typography.titleMedium)
    Text(
      "The headset couldn't find where these were pinned. Remove them and pin them again.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (pin in lost) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(pinnedEntity(pin, snapshot).name, Modifier.weight(1f))
        TextButton(onClick = { onRemove(pin) }) { Text("Remove") }
      }
    }
  }
}
