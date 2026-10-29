package ee.schimke.terrazzo.room

import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.room.CurrentRoom
import ee.schimke.ha.room.DashboardImportance
import ee.schimke.ha.room.HaArea
import ee.schimke.ha.room.RoomControls
import ee.schimke.ha.room.RoomEntity
import ee.schimke.ha.room.currentRoom
import ee.schimke.ha.room.roomControls
import ee.schimke.ha.room.roomTrackerCandidates
import ee.schimke.ha.room.roomsWithControls
import kotlinx.serialization.json.JsonPrimitive

/** The room on show and why, its controls, and the other rooms to switch to. */
data class RoomState(
  val current: CurrentRoom,
  val controls: RoomControls?,
  val rooms: List<HaArea>,
  val trackerCandidates: List<String>,
)

/**
 * The [RoomState] under [snapshot]: the current room ([currentRoom], from the room the user
 * [picked], the [nearestPin] and the [tracker]) or, when nothing says, the room the dashboards care
 * most about, with its controls ranked by [importance].
 */
fun roomState(
  snapshot: HaSnapshot,
  importance: DashboardImportance,
  picked: String?,
  tracker: String?,
  nearestPin: String? = null,
): RoomState {
  val rooms = roomsWithControls(snapshot, importance)
  val current = currentRoom(snapshot, picked, tracker, nearestPin)
  val area = current.area ?: rooms.firstOrNull()
  return RoomState(
    current = current,
    controls = area?.let { roomControls(it, snapshot, importance) },
    rooms = rooms,
    trackerCandidates = roomTrackerCandidates(snapshot),
  )
}

/** Why the room on show is the one it is, in a line for the header. */
fun sourceLabel(state: RoomState): String {
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

/**
 * This instance's pinned controls, and what to do as a spatial room pins, moves, unpins and finds
 * them.
 */
class RoomPinning(
  val pins: List<RoomPin>,
  val onPinned: (anchorId: String, entityId: String) -> Unit,
  val onMoved: (old: RoomPin, anchorId: String) -> Unit,
  val onUnpinned: (RoomPin) -> Unit,
  val onNearestPin: (String?) -> Unit,
)

/** [pin]'s entity as a room entry, named as Home Assistant names it, for a pinned panel. */
fun pinnedEntity(pin: RoomPin, snapshot: HaSnapshot?): RoomEntity {
  val state = snapshot?.states?.get(pin.entityId)
  val name =
    (state?.attributes?.get("friendly_name") as? JsonPrimitive)?.takeIf { it.isString }?.content
      ?: pin.entityId
  return RoomEntity(pin.entityId, name, importance = 0f, active = state?.state == "on")
}
