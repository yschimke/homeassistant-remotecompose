package ee.schimke.terrazzo.room

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalHaActionDispatcher
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.room.CurrentRoom
import ee.schimke.ha.room.DashboardImportance
import ee.schimke.ha.room.HaArea
import ee.schimke.ha.room.RoomControls
import ee.schimke.ha.room.RoomEntity
import ee.schimke.ha.room.currentRoom
import ee.schimke.ha.room.roomControls
import ee.schimke.ha.room.roomTrackerCandidates
import ee.schimke.ha.room.roomsWithControls
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.withLiveStates
import ee.schimke.terrazzo.dashboard.DashboardListState
import ee.schimke.terrazzo.dashboard.EntityControlsPanel
import ee.schimke.terrazzo.dashboard.currentScreenSkin
import ee.schimke.terrazzo.dashboard.entityControls
import ee.schimke.terrazzo.dashboard.rememberDashboardActionDispatcher
import ee.schimke.terrazzo.dashboard.rememberSelectedDashboardListState
import ee.schimke.terrazzo.dashboard.skinnedAccent
import ee.schimke.terrazzo.feedback.rememberInteractionFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Controls for the room the user is in: the dashboards say which entities matter, Home Assistant's
 * areas say where each one is, and a room tracker or presence sensor says where the user is
 * ([currentRoom]). Turn off the room's heater or turn down its TV without finding the card.
 *
 * On an XR headset in full space the room surrounds the user ([SpatialRoomControls]): a panel per
 * control on a curve at arm's length, the room's header and the rest of its controls beside them.
 * Everywhere else (a phone, a tablet, the headset's home space) it is a flat grid, with a way into
 * full space where the device has one.
 */
@Composable
fun RoomScreen(session: HaSession, readOnly: Boolean, onBack: () -> Unit) {
  val prefs = LocalTerrazzoGraph.current.preferencesStore
  val scope = rememberCoroutineScope()
  val picked by prefs.roomPicked.collectAsState(initial = null)
  val tracker by prefs.roomTracker.collectAsState(initial = null)
  val pinsEncoded by prefs.roomPins.collectAsState(initial = null)
  val allPins = remember(pinsEncoded) { decodeRoomPins(pinsEncoded) }
  val pins = remember(allPins, session.baseUrl) { allPins.filter { it.baseUrl == session.baseUrl } }
  // The pinned control the user stands nearest, while the spatial room finds pins' places.
  var nearestPin by remember { mutableStateOf<String?>(null) }

  val loaded = rememberRoomData(session)
  val polled = rememberPolledSnapshot(session)
  val liveState by session.liveStates.collectAsState()
  val live = liveState
  // The loaded snapshot (polled, for a session that asks to be: demo mode) keeps the instance's
  // metadata, the temperature unit among it; live states replace its states as they arrive.
  val base = polled ?: loaded?.snapshot
  val snapshot =
    when {
      base == null -> live
      live == null -> base
      else -> base.withLiveStates(live).copy(areas = live.areas.ifEmpty { base.areas })
    }

  val feedback = rememberInteractionFeedback()
  val dispatcher =
    rememberDashboardActionDispatcher(
      session,
      readOnly = readOnly,
      feedback = feedback,
      stateOf = { id -> snapshot?.states?.get(id)?.state },
    )

  val state =
    remember(snapshot, loaded?.importance, picked, tracker, nearestPin) {
      snapshot?.let {
        roomState(it, loaded?.importance ?: DashboardImportance.Empty, picked, tracker, nearestPin)
      }
    }
  val onPick: (String?) -> Unit = { id -> scope.launch { prefs.setRoomPicked(id) } }
  val onFollow: (String?) -> Unit = { id -> scope.launch { prefs.setRoomTracker(id) } }
  val pinning =
    RoomPinning(
      pins = pins,
      onPinned = { anchorId, entityId ->
        val pin = RoomPin(anchorId, session.baseUrl, entityId)
        scope.launch { prefs.setRoomPins(encodeRoomPins(allPins + pin)) }
      },
      onUnpinned = { pin ->
        scope.launch {
          prefs.setRoomPins(encodeRoomPins(allPins.filter { it.anchorId != pin.anchorId }))
        }
      },
      onNearestPin = { nearestPin = it },
    )

  CompositionLocalProvider(LocalHaActionDispatcher provides dispatcher) {
    val content: RoomContent =
      RoomContent(
        state = state,
        snapshot = snapshot,
        readOnly = readOnly,
        tracker = tracker,
        onPick = onPick,
        onFollow = onFollow,
        onAction = dispatcher::dispatch,
      )
    SpatialRoomHost(
      spatial = { exit ->
        // Leaving the room leaves full space too: the dashboards are a flat screen.
        SpatialRoomControls(
          content,
          pinning,
          onBack = {
            exit()
            onBack()
          },
          onCollapse = exit,
        )
      },
      flat = { enter -> FlatRoomControls(content, onBack = onBack, onEnterSpace = enter) },
    )
  }
}

/** What both layouts draw from. */
internal class RoomContent(
  val state: RoomState?,
  val snapshot: HaSnapshot?,
  val readOnly: Boolean,
  val tracker: String?,
  val onPick: (String?) -> Unit,
  val onFollow: (String?) -> Unit,
  val onAction: (HaAction) -> Unit,
)

/**
 * This instance's pinned controls, and what to do as the spatial room pins, unpins and finds them.
 */
internal class RoomPinning(
  val pins: List<RoomPin>,
  val onPinned: (anchorId: String, entityId: String) -> Unit,
  val onUnpinned: (RoomPin) -> Unit,
  val onNearestPin: (String?) -> Unit,
)

/** The room on show and why, its controls, and the other rooms to switch to. */
internal data class RoomState(
  val current: CurrentRoom,
  val controls: RoomControls?,
  val rooms: List<HaArea>,
  val trackerCandidates: List<String>,
)

internal fun roomState(
  snapshot: HaSnapshot,
  importance: DashboardImportance,
  picked: String?,
  tracker: String?,
  nearestPin: String? = null,
): RoomState {
  val rooms = roomsWithControls(snapshot, importance)
  val current = currentRoom(snapshot, picked, tracker, nearestPin)
  // Nothing says where the user is: show the room the dashboards care most about.
  val area = current.area ?: rooms.firstOrNull()
  return RoomState(
    current = current,
    controls = area?.let { roomControls(it, snapshot, importance) },
    rooms = rooms,
    trackerCandidates = roomTrackerCandidates(snapshot),
  )
}

/**
 * The session's snapshot reloaded every [HaSession.refreshIntervalMillis], for a session that polls
 * instead of pushing live states (demo mode, so its simulated changes and the user's own taps
 * show); null for any other session.
 */
@Composable
private fun rememberPolledSnapshot(session: HaSession): HaSnapshot? {
  val polled by
    produceState<HaSnapshot?>(initialValue = null, session) {
      val interval = session.refreshIntervalMillis ?: return@produceState
      while (true) {
        try {
          value = session.loadSnapshot()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // Keep the last snapshot; the next tick tries again.
        }
        delay(interval)
      }
    }
  return polled
}

/** The selected dashboards, scored, and the snapshot the first of them loaded with. */
private class RoomData(val importance: DashboardImportance, val snapshot: HaSnapshot?)

@Composable
private fun rememberRoomData(session: HaSession): RoomData? {
  val dashboards by rememberSelectedDashboardListState(session)
  val ready = (dashboards as? DashboardListState.Ready)?.dashboards
  val loaded by
    produceState<RoomData?>(initialValue = null, session, ready) {
      if (ready == null) return@produceState
      var snapshot: HaSnapshot? = null
      val boards = mutableListOf<Dashboard>()
      for (summary in ready) {
        val pair =
          session.dashboardNow(summary.urlPath)
            ?: try {
              session.loadDashboard(summary.urlPath)
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              null
            }
            ?: continue
        boards += pair.first
        if (snapshot == null) snapshot = pair.second
        // Each dashboard adds to the picture: show what is known so far.
        value = RoomData(DashboardImportance.of(boards), snapshot)
      }
      if (boards.isEmpty()) {
        val bare =
          try {
            session.loadSnapshot()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            null
          }
        value = RoomData(DashboardImportance.Empty, bare)
      }
    }
  return loaded
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlatRoomControls(
  content: RoomContent,
  onBack: () -> Unit,
  onEnterSpace: (() -> Unit)?,
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
          onEnterSpace?.let { SurroundButton(it) }
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
      items(controls.primary, key = { it.entityId }) { RoomEntityCard(it, content) }
      if (controls.more.isNotEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
          Text(
            "More in ${controls.area.name}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
        items(controls.more, key = { it.entityId }) { RoomEntityCard(it, content) }
      }
    }
  }
}

/** Where the room came from, and the other rooms to switch to: automatic first. */
@Composable
internal fun RoomHeader(content: RoomContent, modifier: Modifier = Modifier) {
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

internal fun sourceLabel(state: RoomState): String {
  val area = state.current.area
  return when (state.current.source) {
    CurrentRoom.Source.Picked -> "You picked ${area?.name}"
    CurrentRoom.Source.Pinned -> "You're in ${area?.name}, by the controls you pinned here"
    CurrentRoom.Source.Tracker -> "You're in ${area?.name}, by your room tracker"
    CurrentRoom.Source.Presence -> "Someone's in ${area?.name}, by its presence sensor"
    CurrentRoom.Source.None ->
      state.controls?.let { "Not sure where you are: showing ${it.area.name}" }
        ?: "Not sure where you are"
  }
}

/** Picks the entity that follows the user between rooms, from those naming a room now. */
@Composable
private fun RoomTrackerMenu(content: RoomContent) {
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
      val name = content.snapshot?.states?.get(id)?.friendlyName() ?: id
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
 * One entity: its icon, name and current state, then its controls (a heater's setpoint and mode, a
 * TV's volume and play / pause), as its more-info sheet shows them.
 */
@Composable
internal fun RoomEntityCard(
  entity: RoomEntity,
  content: RoomContent,
  modifier: Modifier = Modifier,
) {
  val snapshot = content.snapshot ?: return
  val state = snapshot.states[entity.entityId]
  val accent =
    skinnedAccent(
      remember(state) { state?.let { HaStateColor.activeFor(it) } ?: HaStateColor.Neutral }
    )
  val controls =
    remember(entity.entityId, state, snapshot.temperatureUnit) {
      entityControls(entity.entityId, state, snapshot.temperatureUnit)
    }
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    EntityHeading(entity, state, snapshot, accent)
    EntityControlsPanel(controls, accent, content.readOnly, content.onAction)
  }
}

@Composable
private fun EntityHeading(
  entity: RoomEntity,
  state: EntityState?,
  snapshot: HaSnapshot,
  accent: Color,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    val icon = remember(state) { state?.let { HaIconMap.resolveForState(null, it) } }
    Box(
      Modifier.size(40.dp).background(accent.copy(alpha = 0.18f), currentScreenSkin().badgeShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        icon ?: HaIconMap.Fallback,
        contentDescription = null,
        tint = accent,
        modifier = Modifier.size(22.dp),
      )
    }
    Column(Modifier.weight(1f)) {
      Text(
        entity.name,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        state?.let { formatState(it, snapshot) } ?: "Unavailable",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

private fun EntityState.friendlyName(): String? =
  (attributes["friendly_name"] as? kotlinx.serialization.json.JsonPrimitive)
    ?.takeIf { it.isString }
    ?.content
