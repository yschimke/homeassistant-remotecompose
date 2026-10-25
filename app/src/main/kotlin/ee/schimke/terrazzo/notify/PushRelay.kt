package ee.schimke.terrazzo.notify

import android.util.Log
import ee.schimke.ha.client.HaPushNotification
import ee.schimke.terrazzo.BuildConfig
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.core.mobileapp.PushTarget
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Notifications while the app holds no websocket: HA posts them to the Terrazzo push relay
 * (`app_data.push_url`, [BuildConfig.PUSH_RELAY_URL]), which forwards each to this install's FCM
 * token as a data message ([fromRelay]). See `docs/architecture/notifications.md`.
 *
 * FCM lives in the Firebase source set, compiled in only with `google-services.json` (as
 * Crashlytics is), so it is found reflectively ([PushTokenSource]); a build without it has no relay
 * and notifications arrive over the websocket only.
 */
object PushRelay {
  private const val TAG = "PushRelay"
  private const val TOKEN_TIMEOUT_MS = 5_000L

  /** This build's FCM token source, or null without Firebase. */
  private val tokens: PushTokenSource? by lazy {
    runCatching {
      Class.forName("ee.schimke.terrazzo.notify.FirebasePushTokens")
        .getDeclaredConstructor()
        .newInstance() as PushTokenSource
    }
      .getOrNull()
  }

  /** The relay target to register with HA, or null (no Firebase, no relay, no token yet). */
  suspend fun target(): PushTarget? {
    val url = BuildConfig.PUSH_RELAY_URL.ifEmpty { null } ?: return null
    val source = tokens ?: return null
    val token =
      withTimeoutOrNull(TOKEN_TIMEOUT_MS) { runCatching { source.token() }.getOrNull() }
        ?: return null
    return PushTarget(url, token)
  }

  /**
   * Hands HA [target] (the current token, by default) for [baseUrl]'s registration when HA holds
   * another or none. Best effort: HA learns it at the next call otherwise.
   */
  suspend fun sync(graph: TerrazzoGraph, baseUrl: String, target: PushTarget? = null) {
    val push = target ?: target() ?: return
    val stored = runCatching { graph.mobileAppStore.get(baseUrl) }.getOrNull() ?: return
    if (stored.pushToken == push.token) return
    runCatching { graph.mobileAppRegistrar.updatePush(baseUrl, push) }
      .onFailure { Log.w(TAG, "Couldn't give HA the push relay token", it) }
  }

  /** [sync] for every registered server: FCM issued a new token. */
  suspend fun syncAll(graph: TerrazzoGraph, token: String) {
    val url = BuildConfig.PUSH_RELAY_URL.ifEmpty { null } ?: return
    for (baseUrl in graph.mobileAppStore.instances()) sync(graph, baseUrl, PushTarget(url, token))
  }

  /**
   * The HA message in a relayed FCM data payload: `message`, `title`, `data` (HA's `data`, as JSON)
   * and `webhook_id`, which names the registration, so the server, it was sent to.
   */
  fun fromRelay(payload: Map<String, String>): HaPushNotification? {
    val event = buildJsonObject {
      payload["message"]?.let { put("message", JsonPrimitive(it)) }
      payload["title"]?.let { put("title", JsonPrimitive(it)) }
      payload["data"]
        ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        ?.let { put("data", it) }
    }
    return HaPushNotification.parse(event)
  }
}

/** An FCM token, from the Firebase source set's `FirebasePushTokens`. */
interface PushTokenSource {
  suspend fun token(): String?
}
