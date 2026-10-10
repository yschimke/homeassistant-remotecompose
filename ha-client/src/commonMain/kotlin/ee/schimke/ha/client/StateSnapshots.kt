package ee.schimke.ha.client

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Fetch once, then apply state_changed deltas; the caller owns reconnect/resubscription. */
fun stateSnapshots(events: Flow<JsonObject>, initial: suspend () -> HaSnapshot): Flow<HaSnapshot> =
  channelFlow {
    val mutex = Mutex()
    var snapshot = HaSnapshot()
    // Start collection before fetching initial state, and hold deltas until that fetch lands.
    mutex.withLock {
      launch(start = CoroutineStart.UNDISPATCHED) {
        events.collect { event ->
          mutex.withLock {
            val next = snapshot.withStateEvent(event)
            if (next != snapshot) {
              snapshot = next
              send(next)
            }
          }
        }
      }
      snapshot = initial()
      send(snapshot)
    }
  }

private val stateJson = Json { ignoreUnknownKeys = true }

internal fun HaSnapshot.withStateEvent(event: JsonObject): HaSnapshot {
  val data = event["data"] as? JsonObject ?: return this
  val id = (data["entity_id"] as? JsonPrimitive)?.contentOrNull ?: return this
  if (!data.containsKey("new_state")) return this
  val raw = data["new_state"]
  val next =
    if (raw == JsonNull) null
    else
      raw?.let {
        stateJson.decodeFromJsonElement<EntityState>(
          buildJsonObject {
            (it as JsonObject).forEach { (key, value) ->
              put(
                when (key) {
                  "entity_id" -> "entityId"
                  "last_changed" -> "lastChanged"
                  "last_updated" -> "lastUpdated"
                  else -> key
                },
                value,
              )
            }
          }
        )
      }
  return copy(states = if (next == null) states - id else states + (id to next))
}
