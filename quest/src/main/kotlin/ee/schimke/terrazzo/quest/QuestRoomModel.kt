package ee.schimke.terrazzo.quest

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ee.schimke.ha.room.DashboardImportance
import ee.schimke.terrazzo.room.RoomPin
import ee.schimke.terrazzo.room.RoomState
import ee.schimke.terrazzo.room.decodeRoomPins
import ee.schimke.terrazzo.room.encodeRoomPins
import ee.schimke.terrazzo.room.roomState

/**
 * The Quest room's state, outside any composition (a Spatial SDK activity isn't a
 * `ComponentActivity`): the house's snapshot, the room the user picked, the tracker they follow and
 * their pins, kept in [prefs].
 *
 * The house is [QuestDemoHouse] for now, toggled in memory; signing in to a Home Assistant (through
 * `:ha-client`) is still to come on Quest.
 */
class QuestRoomModel(private val prefs: SharedPreferences) {
  private val baseUrl = QuestDemoHouse.BASE_URL

  var snapshot by mutableStateOf(QuestDemoHouse.snapshot)
    private set

  /** No dashboards on the demo house: every control ranks by its room and state alone. */
  val importance: DashboardImportance = DashboardImportance.Empty

  var picked by mutableStateOf(prefs.getString(PICKED, null))
    private set

  var tracker by mutableStateOf(prefs.getString(TRACKER, null))
    private set

  var nearestPin by mutableStateOf<String?>(null)

  var pins by
    mutableStateOf(decodeRoomPins(prefs.getString(PINS, null)).filter { it.baseUrl == baseUrl })
    private set

  /** The room on show, its controls and the rooms to switch to. */
  val state: RoomState
    get() = roomState(snapshot, importance, picked, tracker, nearestPin)

  fun pick(areaId: String?) {
    picked = areaId
    prefs.edit().putString(PICKED, areaId).apply()
  }

  fun follow(entityId: String?) {
    tracker = entityId
    prefs.edit().putString(TRACKER, entityId).apply()
  }

  fun toggle(entityId: String) {
    snapshot = QuestDemoHouse.toggled(snapshot, entityId)
  }

  fun pinned(anchorId: String, entityId: String) =
    savePins(pins + RoomPin(anchorId, baseUrl, entityId))

  fun unpinned(pin: RoomPin) = savePins(pins.filter { it.anchorId != pin.anchorId })

  private fun savePins(updated: List<RoomPin>) {
    pins = updated
    // Pins of other instances stay as they are.
    val others = decodeRoomPins(prefs.getString(PINS, null)).filter { it.baseUrl != baseUrl }
    prefs.edit().putString(PINS, encodeRoomPins(others + updated)).apply()
  }

  private companion object {
    const val PICKED = "room_picked"
    const val TRACKER = "room_tracker"
    const val PINS = "room_pins"
  }
}
