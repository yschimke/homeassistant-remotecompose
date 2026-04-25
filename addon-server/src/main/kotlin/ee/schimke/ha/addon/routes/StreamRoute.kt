package ee.schimke.ha.addon.routes

import ee.schimke.ha.addon.bridge.HaEvent
import ee.schimke.ha.addon.bridge.HaSupervisorBridge
import io.ktor.server.routing.Routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Per-client WebSocket. Wire format mirrors PLAN.md §"WebSocket /v1/stream".
 *
 * Client subscribes to a set of `entity_id`s; server pushes state updates keyed by the same names
 * that `LiveBindings` bakes into the `.rc` document, so the client can hand the map straight to its
 * `RemoteDocumentPlayer` named-state setter.
 *
 * Per-client filtering is done in-process — the bridge holds a global `state_changed` subscription
 * against HA, so the cost of a new client is a Set membership check per event.
 */
fun Routing.streamRoute(bridge: HaSupervisorBridge) {
  val log = LoggerFactory.getLogger("ee.schimke.ha.addon.StreamRoute")
  val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  webSocket("/v1/stream") {
    val subscribed = mutableSetOf<String>()
    val mutex = kotlinx.coroutines.sync.Mutex()

    send(Frame.Text("""{"type":"ready"}"""))

    // Fan-out coroutine: every HA event we care about → filtered push.
    val pump = launch {
      bridge.events.collect { event ->
        when (event) {
          is HaEvent.StateChanged -> {
            val match = mutex.withLockReturn { event.entityId in subscribed }
            if (!match) return@collect
            val state = bridge.cache.get(event.entityId) ?: return@collect
            val msg = buildJsonObject {
              put("type", "state")
              put(
                "bindings",
                buildJsonObject {
                  put("${state.entityId}.state", JsonPrimitive(state.state))
                  // `is_on` is the only typed binding the
                  // client knows by default; emitting it
                  // for everything is harmless — clients
                  // ignore unknown names.
                  put("${state.entityId}.is_on", JsonPrimitive(state.state == "on"))
                },
              )
            }
            send(Frame.Text(msg.toString()))
          }
          is HaEvent.LovelaceUpdated -> {
            send(
              Frame.Text(
                buildJsonObject {
                    put("type", "lovelace_updated")
                    if (event.urlPath != null) put("url_path", event.urlPath)
                  }
                  .toString()
              )
            )
          }
        }
      }
    }

    try {
      for (frame in incoming) {
        if (frame !is Frame.Text) continue
        val text = frame.readText()
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
        handleClientFrame(obj, subscribed, mutex, bridge)?.let { reply ->
          send(Frame.Text(reply.toString()))
        }
      }
    } catch (t: Throwable) {
      log.warn("stream client error: {}", t.message)
    } finally {
      pump.cancel()
    }
  }
}

private suspend fun handleClientFrame(
  obj: JsonObject,
  subscribed: MutableSet<String>,
  mutex: kotlinx.coroutines.sync.Mutex,
  bridge: HaSupervisorBridge,
): JsonObject? {
  val id = obj["id"]?.jsonPrimitive?.intOrNull
  val type = obj["type"]?.jsonPrimitive?.content ?: return errorReply(id, "missing type")
  return when (type) {
    "subscribe" -> {
      val entities =
        obj["entities"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
          ?: return errorReply(id, "missing entities")
      mutex.withLockReturn { subscribed.addAll(entities) }
      // Hydrate: send current state for newly-subscribed entities so
      // the client doesn't have to wait for the next `state_changed`
      // before its document looks right.
      val bindings = entities.mapNotNull { entityId ->
        val s = bridge.cache.get(entityId) ?: return@mapNotNull null
        entityId to s
      }
      buildJsonObject {
        put("type", "state")
        put(
          "bindings",
          buildJsonObject {
            for ((entityId, state) in bindings) {
              put("$entityId.state", JsonPrimitive(state.state))
              put("$entityId.is_on", JsonPrimitive(state.state == "on"))
            }
          },
        )
      }
    }
    "unsubscribe" -> {
      val entities =
        obj["entities"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
          ?: return errorReply(id, "missing entities")
      mutex.withLockReturn { subscribed.removeAll(entities.toSet()) }
      null
    }
    "call_service" -> {
      val domain = obj["domain"]?.jsonPrimitive?.content ?: return errorReply(id, "missing domain")
      val service =
        obj["service"]?.jsonPrimitive?.content ?: return errorReply(id, "missing service")
      val target = obj["target"]?.jsonObject
      val entityId = target?.get("entity_id")?.jsonPrimitive?.content
      val data = obj["service_data"]?.jsonObject
      val result = bridge.callService(domain, service, entityId, data)
      buildJsonObject {
        if (id != null) put("id", JsonPrimitive(id))
        put("type", "result")
        put("result", result)
      }
    }
    else -> errorReply(id, "unknown type=$type")
  }
}

private fun errorReply(id: Int?, message: String): JsonObject = buildJsonObject {
  if (id != null) put("id", JsonPrimitive(id))
  put("type", "error")
  put("message", message)
}

// Minimal lock helper that returns the block's result. The stdlib has
// `withLock` already; this just keeps the call-sites a little tidier.
private suspend fun <T> kotlinx.coroutines.sync.Mutex.withLockReturn(block: () -> T): T {
  lock()
  return try {
    block()
  } finally {
    unlock()
  }
}
