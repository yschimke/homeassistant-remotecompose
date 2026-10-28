package ee.schimke.ha.room

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlinx.serialization.json.JsonPrimitive

/**
 * Which room the user is in, and how that was decided.
 *
 * In order: a room the user [picked][Source.Picked] wins until they go back to automatic; then a
 * room-tracker entity's state ([Source.Tracker]): Bermuda's `sensor.<phone>_area`, an ESPresense or
 * room-assistant sensor, or anything else whose state (or `area` / `area_id` / `room` attribute)
 * names an area; then the area whose presence, occupancy or motion sensor most recently turned on
 * ([Source.Presence]). With none of those it is null, and the host shows its best room.
 */
data class CurrentRoom(val area: HaArea?, val source: Source) {
  enum class Source {
    Picked,
    Tracker,
    Presence,
    None,
  }
}

/**
 * The [CurrentRoom] under [snapshot], given the room the user [picked] (an area id, or null for
 * automatic) and the [trackerEntityId] they chose to follow, if any.
 */
fun currentRoom(snapshot: HaSnapshot, picked: String?, trackerEntityId: String?): CurrentRoom {
  picked?.let { id ->
    snapshot.areas[id]?.let {
      return CurrentRoom(it, CurrentRoom.Source.Picked)
    }
  }
  trackerEntityId
    ?.let { snapshot.states[it] }
    ?.let { trackedArea(it, snapshot) }
    ?.let {
      return CurrentRoom(it, CurrentRoom.Source.Tracker)
    }
  presenceArea(snapshot)?.let {
    return CurrentRoom(it, CurrentRoom.Source.Presence)
  }
  return CurrentRoom(null, CurrentRoom.Source.None)
}

/**
 * Entities that could follow the user from room to room: those whose state, or `area` / `area_id` /
 * `room` attribute, names one of the snapshot's areas right now. What a host offers to pick a room
 * tracker from.
 */
fun roomTrackerCandidates(snapshot: HaSnapshot): List<String> =
  snapshot.states.values
    .filter { it.entityId.substringBefore('.') in TRACKER_DOMAINS }
    .filter { trackedArea(it, snapshot) != null }
    .map { it.entityId }
    .sorted()

/** The area [tracker] says the user is in, or null when it names none (`not_home`, `unknown`). */
internal fun trackedArea(tracker: EntityState, snapshot: HaSnapshot): HaArea? {
  TRACKER_ATTRIBUTES.forEach { key ->
    val value = (tracker.attributes[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    value?.let(snapshot::areaNamed)?.let {
      return it
    }
  }
  return snapshot.areaNamed(tracker.state)
}

/**
 * The area whose presence sensor most recently turned on, among those on now. A presence or
 * occupancy sensor outranks a motion one: motion stops while someone sits still.
 */
internal fun presenceArea(snapshot: HaSnapshot): HaArea? =
  snapshot.states.values
    .asSequence()
    .filter { it.entityId.startsWith("binary_sensor.") && it.state == "on" }
    .mapNotNull { state ->
      val deviceClass = (state.attributes["device_class"] as? JsonPrimitive)?.content
      val rank = PRESENCE_CLASSES.indexOf(deviceClass).takeIf { it >= 0 } ?: return@mapNotNull null
      val area = snapshot.areaOf(state.entityId) ?: return@mapNotNull null
      Triple(area, rank, state.lastChanged)
    }
    .sortedWith(
      compareBy<Triple<HaArea, Int, kotlin.time.Instant?>> { it.second }
        .thenByDescending { it.third }
    )
    .firstOrNull()
    ?.first

private val TRACKER_DOMAINS =
  setOf("sensor", "device_tracker", "person", "input_select", "input_text")

private val TRACKER_ATTRIBUTES = listOf("area", "area_id", "room")

/** Presence device classes, strongest first. */
private val PRESENCE_CLASSES = listOf("presence", "occupancy", "motion")
