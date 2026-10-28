package ee.schimke.ha.room

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import kotlinx.serialization.json.JsonPrimitive

/**
 * The controls for one room: what a person standing in it most likely wants to reach — turn off the
 * heater, turn down the TV.
 *
 * [primary] are the room's entities a dashboard shows, best first ([DashboardImportance]), so the
 * user's own choices lead; [more] are the room's other controllable entities, which no dashboard
 * shows but which are still there to reach. Within each, an entity that is on (a heater running, a
 * TV playing) comes ahead of one that is off at the same score: it is the likelier one to want
 * changing.
 */
data class RoomControls(
  val area: HaArea,
  val primary: List<RoomEntity>,
  val more: List<RoomEntity>,
) {
  val isEmpty: Boolean
    get() = primary.isEmpty() && more.isEmpty()
}

/**
 * One entity of [RoomControls]: its id, the name to show, its dashboard score and whether it's on.
 */
data class RoomEntity(
  val entityId: String,
  val name: String,
  val importance: Float,
  val active: Boolean,
) {
  val domain: String
    get() = entityId.substringBefore('.')
}

/**
 * [area]'s [RoomControls] under [snapshot], ranked by [importance]. Only entities a person controls
 * count (lights, switches, climate, media players, covers…); sensors are read, not controlled.
 * Entities Home Assistant hides or files as configuration or diagnostics are left out, as its own
 * area pages leave them out. [primaryLimit] caps [RoomControls.primary]; the rest of the
 * dashboard's entities move to the front of [RoomControls.more].
 */
fun roomControls(
  area: HaArea,
  snapshot: HaSnapshot,
  importance: DashboardImportance,
  primaryLimit: Int = DEFAULT_PRIMARY_LIMIT,
): RoomControls {
  val candidates =
    snapshot.entitiesIn(area.id).mapNotNull { id ->
      val state = snapshot.states[id] ?: return@mapNotNull null
      val info = snapshot.devices[id]
      if (info?.hidden == true || info?.entityCategory != null) return@mapNotNull null
      if (!isRoomControl(id)) return@mapNotNull null
      RoomEntity(
        entityId = id,
        name = displayName(state, area),
        importance = importance.of(id),
        active = HaLiveBindings.isOn(id, state.state),
      )
    }
  val ranked =
    candidates.sortedWith(
      compareByDescending<RoomEntity> { it.importance }
        .thenByDescending { it.active }
        .thenBy { DOMAIN_ORDER.indexOf(it.domain).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        .thenBy { it.name.lowercase() }
    )
  val shown = ranked.filter { it.importance > 0f }
  return RoomControls(
    area = area,
    primary = shown.take(primaryLimit),
    more = shown.drop(primaryLimit) + ranked.filter { it.importance <= 0f },
  )
}

/**
 * The areas worth offering as rooms, best first: those with something to control, ordered by how
 * much of what the dashboards show is in them (an `area` card counts for its area), then by name.
 */
fun roomsWithControls(snapshot: HaSnapshot, importance: DashboardImportance): List<HaArea> {
  val score = HashMap<String, Float>()
  val controllable = HashSet<String>()
  for (id in snapshot.states.keys) {
    val info = snapshot.devices[id] ?: continue
    val areaId = info.areaId ?: continue
    if (info.hidden || info.entityCategory != null || !isRoomControl(id)) continue
    controllable += areaId
    score[areaId] = (score[areaId] ?: 0f) + importance.of(id)
  }
  importance.areas.forEach { (areaId, s) -> score[areaId] = (score[areaId] ?: 0f) + s }
  return snapshot.areas.values
    .filter { it.id in controllable }
    .sortedWith(compareByDescending<HaArea> { score[it.id] ?: 0f }.thenBy { it.name.lowercase() })
}

/** Whether [entityId]'s domain is one a person controls rather than reads. */
fun isRoomControl(entityId: String): Boolean = entityId.substringBefore('.') in DOMAIN_ORDER

/**
 * The entity's `friendly_name` with the room's name taken off the front, as Home Assistant's area
 * page shows it: in the Living Room, `Living Room Lamp` is just `Lamp`.
 */
internal fun displayName(state: EntityState, area: HaArea): String {
  val friendly =
    (state.attributes["friendly_name"] as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.content
      ?.takeIf { it.isNotBlank() } ?: return state.entityId
  val prefix = area.name.trim()
  if (prefix.isEmpty() || !friendly.startsWith(prefix, ignoreCase = true)) return friendly
  val rest = friendly.substring(prefix.length).trimStart(' ', '-', ':')
  return rest.ifEmpty { friendly }.replaceFirstChar { it.uppercaseChar() }
}

const val DEFAULT_PRIMARY_LIMIT: Int = 6

/** Controllable domains, in the order a room shows them when nothing else separates two. */
private val DOMAIN_ORDER =
  listOf(
    "climate",
    "media_player",
    "light",
    "fan",
    "cover",
    "humidifier",
    "water_heater",
    "switch",
    "input_boolean",
    "valve",
    "vacuum",
    "lock",
    "scene",
    "script",
    "button",
    "input_button",
    "select",
    "input_select",
    "number",
    "input_number",
    "siren",
  )
