package ee.schimke.terrazzo.glasses

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ee.schimke.terrazzo.room.DemoHouse
import ee.schimke.terrazzo.room.RoomState
import ee.schimke.terrazzo.room.roomState

/**
 * The room both screens show: the phone's and the glasses'. Both activities run in the phone app's
 * process, so one holder is the whole sync: pick a room or toggle a control on either and the other
 * recomposes.
 *
 * The house is [DemoHouse] for now, toggled in memory; signing in to a Home Assistant (through
 * `:ha-client`) is still to come here, as on Quest.
 */
object GlassesRoom {
  var snapshot by mutableStateOf(DemoHouse.snapshot)
    private set

  var picked by mutableStateOf<String?>(null)
    private set

  var tracker by mutableStateOf<String?>(null)
    private set

  /** The room on show, its controls and the rooms to switch to. */
  val state: RoomState
    get() = roomState(snapshot, DemoHouse.importance, picked, tracker)

  fun pick(areaId: String?) {
    picked = areaId
  }

  fun follow(entityId: String?) {
    tracker = entityId
  }

  fun toggle(entityId: String) {
    snapshot = DemoHouse.toggled(snapshot, entityId)
  }
}
