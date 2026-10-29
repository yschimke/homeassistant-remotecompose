package ee.schimke.terrazzo.room

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A control the user pinned to a place in their home on an XR headset: the persisted anchor that
 * holds the place ([anchorId], the UUID ARCore for XR gave it), the Home Assistant instance
 * ([baseUrl]) and the entity whose controls show there.
 *
 * The anchor lives in the headset's own storage; this is the app's side of it, kept in
 * `PreferencesStore.roomPins`.
 */
@Serializable data class RoomPin(val anchorId: String, val baseUrl: String, val entityId: String)

private val pinListSerializer = ListSerializer(RoomPin.serializer())

private val pinJson = Json { ignoreUnknownKeys = true }

/** [encoded] as pins; none for null or anything that doesn't parse. */
fun decodeRoomPins(encoded: String?): List<RoomPin> =
  encoded?.let { runCatching { pinJson.decodeFromString(pinListSerializer, it) }.getOrNull() }
    ?: emptyList()

fun encodeRoomPins(pins: List<RoomPin>): String = pinJson.encodeToString(pinListSerializer, pins)

/**
 * Of [placed] (each pin's entity with its distance from the user in metres, for the pins whose
 * place the headset found), the entity of the nearest within [reach]: what says which room the user
 * stands in. Farther than [reach], a pin is in view but not where the user is.
 */
fun nearestPinnedEntity(
  placed: List<Pair<String, Float>>,
  reach: Float = PIN_REACH_METRES,
): String? = placed.filter { it.second <= reach }.minByOrNull { it.second }?.first

/** About a room's width: a pin nearer than this is in the room the user stands in. */
const val PIN_REACH_METRES = 4f
