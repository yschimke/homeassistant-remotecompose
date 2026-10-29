package ee.schimke.terrazzo.room

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import ee.schimke.ha.room.DashboardImportance
import ee.schimke.ha.room.RoomEntity
import ee.schimke.ha.room.currentRoom
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
        scope.launch { prefs.updateRoomPins { encodeRoomPins(decodeRoomPins(it) + pin) } }
      },
      onMoved = { old, anchorId ->
        val moved = old.copy(anchorId = anchorId)
        scope.launch {
          prefs.updateRoomPins { stored ->
            encodeRoomPins(
              decodeRoomPins(stored).map { if (it.anchorId == old.anchorId) moved else it }
            )
          }
        }
      },
      onUnpinned = { pin ->
        scope.launch {
          prefs.updateRoomPins { stored ->
            encodeRoomPins(decodeRoomPins(stored).filter { it.anchorId != pin.anchorId })
          }
        }
      },
      onNearestPin = { nearestPin = it },
    )

  CompositionLocalProvider(LocalHaActionDispatcher provides dispatcher) {
    val content =
      RoomContent(
        state = state,
        snapshot = snapshot,
        tracker = tracker,
        onPick = onPick,
        onFollow = onFollow,
        entityCard = { entity, modifier ->
          RoomEntityCard(entity, snapshot, readOnly, dispatcher::dispatch, modifier)
        },
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
      flat = { enter ->
        FlatRoomControls(content, onBack = onBack) { enter?.let { SurroundButton(it) } }
      },
    )
  }
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

/**
 * One entity: its icon, name and current state, then its controls (a heater's setpoint and mode, a
 * TV's volume and play / pause), as its more-info sheet shows them.
 */
@Composable
internal fun RoomEntityCard(
  entity: RoomEntity,
  snapshot: HaSnapshot?,
  readOnly: Boolean,
  onAction: (HaAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  snapshot ?: return
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
    EntityControlsPanel(controls, accent, readOnly, onAction)
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
