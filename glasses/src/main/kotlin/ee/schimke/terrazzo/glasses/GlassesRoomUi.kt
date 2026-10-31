package ee.schimke.terrazzo.glasses

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.xr.glimmer.GlimmerTheme
import androidx.xr.glimmer.ListItem
import androidx.xr.glimmer.Text
import androidx.xr.glimmer.TitleChip
import androidx.xr.glimmer.list.GlimmerLazyColumn
import androidx.xr.glimmer.list.items
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.room.RoomEntity
import ee.schimke.terrazzo.room.RoomState
import ee.schimke.terrazzo.room.TOGGLE_DOMAINS

/**
 * The room on the glasses: its name, then its controls, the dashboards' first, a few at most, and
 * the other rooms to switch to. One list the touchpad moves through, a tap acting on the focused
 * row: a control toggles, a room becomes the one on show.
 *
 * Glasses' displays are additive, so black is see-through: the background stays black and only the
 * rows light up.
 */
@Composable
fun GlassesRoomScreen(
  state: RoomState,
  snapshot: HaSnapshot,
  onToggle: (entityId: String) -> Unit,
  onPick: (areaId: String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val controls = state.controls
  val entities = controls?.let { (it.primary + it.more).take(MAX_CONTROLS) }.orEmpty()
  val otherRooms = state.rooms.filter { it.id != controls?.area?.id }
  GlimmerTheme {
    Column(
      modifier.fillMaxSize().background(Color.Black).padding(top = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      TitleChip { Text(controls?.area?.name ?: "No room") }
      GlimmerLazyColumn(Modifier.fillMaxSize()) {
        if (entities.isEmpty()) {
          item { ListItem { Text("Nothing to control here") } }
        }
        items(entities, key = { it.entityId }) { entity ->
          GlassesControl(entity, snapshot, onToggle)
        }
        items(otherRooms, key = { "room:${it.id}" }) { room ->
          ListItem(
            onClick = { onPick(room.id) },
            supportingLabel = { Text("Switch room") },
          ) {
            Text(room.name)
          }
        }
      }
    }
  }
}

/** One control: its name and state, a tap toggling it when it turns on and off. */
@Composable
fun GlassesControl(
  entity: RoomEntity,
  snapshot: HaSnapshot,
  onToggle: (entityId: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val state = snapshot.states[entity.entityId]
  val label = state?.let { formatState(it, snapshot) } ?: "Unavailable"
  val described = modifier.semantics { stateDescription = label }
  if (state != null && entity.domain in TOGGLE_DOMAINS) {
    ListItem(
      onClick = { onToggle(entity.entityId) },
      modifier = described,
      supportingLabel = { Text(label) },
    ) {
      Text(entity.name)
    }
  } else {
    ListItem(modifier = described, supportingLabel = { Text(label) }) { Text(entity.name) }
  }
}

/** Glanceable: a handful of controls, the room's most important. */
internal const val MAX_CONTROLS = 4
