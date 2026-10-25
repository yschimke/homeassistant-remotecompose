package ee.schimke.terrazzo.notify

import android.content.Context
import android.util.Log
import ee.schimke.ha.client.HaPushNotification
import ee.schimke.ha.model.EntityState
import ee.schimke.terrazzo.TerrazzoApplication
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.core.session.HaSession
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Receives Home Assistant's `notify.mobile_app_<device>` messages on the app's live session and
 * posts them ([HaNotifications]). HA delivers them over the websocket's push channel, so they
 * arrive while the app holds its connection (in the foreground); HA's FCM relay only serves the
 * official companion apps.
 *
 * A message's card keeps up with its entity: each live state change redraws the cards whose entity
 * changed and replaces their notifications silently, while they show.
 */
object HaPushReceiver {
  private const val TAG = "HaPushReceiver"

  /** Runs for [session]'s lifetime; returns at once for the demo or an unregistered server. */
  suspend fun run(context: Context, graph: TerrazzoGraph, session: HaSession) {
    if (DemoData.isDemo(session.baseUrl)) return
    val webhookId = webhookId(graph, session) ?: return
    val app = context.applicationContext as TerrazzoApplication
    coroutineScope {
      val watchHasApp =
        app.wearAppInstallHelper.watches
          .map { watches -> watches.any { it.appInstalled } }
          .stateIn(this, SharingStarted.Eagerly, false)
      // Messages showing a card, by dedupe key, and the entity state each card was drawn from.
      val cards = ConcurrentHashMap<String, Pair<HaPushNotification, EntityState?>>()
      launch {
        session.liveStates.filterNotNull().collect { snapshot ->
          for ((key, entry) in cards.entries.toList()) {
            val (notification, drawn) = entry
            val entityId = notification.entityId ?: continue
            val state = snapshot.states[entityId]
            if (state == drawn) continue
            // Dismissed, or replaced by a message without a card: never post it again.
            if (!HaNotifications.isShowing(context, notification)) {
              cards.remove(key)
              continue
            }
            cards[key] = notification to state
            val card =
              runCatching {
                NotificationCards.capture(context, session.baseUrl, entityId, snapshot)
              }
                .onFailure { Log.w(TAG, "Couldn't redraw the card for $entityId", it) }
                .getOrNull() ?: continue
            HaNotifications.handle(
              context,
              session.baseUrl,
              notification,
              watchHasApp.value,
              card,
              refresh = true,
            )
          }
        }
      }
      session.pushNotifications(webhookId).collect { notification ->
        val card = notification.entityId?.let { NotificationCards.render(context, session, it) }
        HaNotifications.handle(context, session.baseUrl, notification, watchHasApp.value, card)
        if (card != null && !notification.isClear) {
          cards[notification.dedupeKey] =
            notification to session.liveStates.value?.states?.get(notification.entityId)
        } else {
          cards.remove(notification.dedupeKey)
        }
      }
    }
  }

  /**
   * This install's `mobile_app` webhook for [session]'s server. A registration made before the push
   * channel existed is made again (the same device id), so HA has somewhere to send.
   */
  private suspend fun webhookId(graph: TerrazzoGraph, session: HaSession): String? {
    val stored = graph.mobileAppStore.get(session.baseUrl)
    if (stored?.pushChannel == true) return stored.webhookId
    val token = session.accessToken ?: return stored?.webhookId
    return runCatching { graph.mobileAppRegistrar.register(session.baseUrl, token).webhookId }
      .onFailure { Log.w(TAG, "mobile_app registration failed", it) }
      .getOrElse { stored?.webhookId }
  }
}
