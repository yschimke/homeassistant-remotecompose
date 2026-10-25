package ee.schimke.terrazzo.notify

import android.content.Context
import android.util.Log
import ee.schimke.terrazzo.BuildConfig
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.core.mobileapp.PushTarget
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.widget.WidgetActionRunner
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Notifications while the app holds no websocket, without their content leaving Home Assistant
 * (`docs/architecture/notifications.md`). The Terrazzo integration is the registration's
 * `push_url`: it holds each message and asks the Terrazzo push relay ([BuildConfig.PUSH_RELAY_URL])
 * for a silent FCM wake naming only the registration. The app wakes ([wake]), connects with its own
 * login and takes the held messages.
 *
 * FCM lives in the Firebase source set, compiled in only with `google-services.json` (as
 * Crashlytics is), so it is found reflectively ([PushTokenSource]). Without it, or without the
 * integration, notifications arrive over the websocket only.
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

  private suspend fun fcmToken(): String? {
    val source = tokens ?: return null
    return withTimeoutOrNull(TOKEN_TIMEOUT_MS) { runCatching { source.token() }.getOrNull() }
  }

  /**
   * On [session] (connected, for registration [webhookId]): registers [fcmToken] with the
   * integration, hands HA its push URL when HA holds another or none, then posts what the
   * integration held. Best effort: without the integration only the websocket delivers.
   */
  suspend fun sync(
    context: Context,
    graph: TerrazzoGraph,
    session: HaSession,
    webhookId: String,
    watchHasApp: Boolean,
    fcmToken: String? = null,
  ) {
    val relay = BuildConfig.PUSH_RELAY_URL.ifEmpty { null } ?: return
    val token = fcmToken ?: fcmToken() ?: return
    runCatching {
      val (url, secret) = session.registerSilentPush(webhookId, token, relay) ?: return
      val stored = graph.mobileAppStore.get(session.baseUrl)
      if (stored != null && (stored.pushUrl != url || stored.pushToken != secret)) {
        graph.mobileAppRegistrar.updatePush(session.baseUrl, PushTarget(url, secret))
      }
      for (held in session.takeHeldNotifications(webhookId)) {
        HaPushReceiver.post(context, session, held, watchHasApp)
      }
    }
      .onFailure { Log.w(TAG, "Silent push sync failed for ${session.baseUrl}", it) }
  }

  /**
   * A silent wake for registration [webhookId] ([fcmToken] given when FCM issued a new one): the
   * app's session if it is connected, else a short-lived one from the stored login.
   */
  suspend fun wake(context: Context, graph: TerrazzoGraph, webhookId: String?, fcmToken: String?) {
    val servers =
      if (webhookId != null) listOfNotNull(graph.mobileAppStore.instanceForWebhook(webhookId))
      else graph.mobileAppStore.instances()
    val watchHasApp = TestNotification.watchHasApp(context)
    for (baseUrl in servers) {
      val registration = graph.mobileAppStore.get(baseUrl) ?: continue
      runCatching {
        WidgetActionRunner.withSession(context, baseUrl) { session ->
          sync(context, graph, session, registration.webhookId, watchHasApp, fcmToken)
        }
      }
        .onFailure { Log.w(TAG, "Couldn't reach $baseUrl after a wake", it) }
    }
  }
}

/** An FCM token, from the Firebase source set's `FirebasePushTokens`. */
interface PushTokenSource {
  suspend fun token(): String?
}
