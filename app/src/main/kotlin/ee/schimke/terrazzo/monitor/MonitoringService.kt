@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.monitor

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.rc.cardHeightDp
import ee.schimke.ha.rc.captureCardDocument
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.terrazzo.MainActivity
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.widget.TerrazzoWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * User-initiated bounded monitoring window for a single card. Runs as
 * a `dataSync` foreground service — no UI surface of its own; the
 * only visible artefact is an ongoing notification whose
 * `CustomBigContentView` is a `RemoteViews` playing the card's RC
 * `DrawInstructions` at the current snapshot. Each tick re-encodes
 * the document with fresh state so values drift in place, and kicks
 * `ACTION_APPWIDGET_UPDATE` so any pinned instance of the same card
 * tracks the same cadence.
 *
 * Scope today: demo mode only — the service pulls snapshots from
 * [DemoData]. Live mode needs a shared non-UI HA session; landing in
 * a follow-up.
 *
 * Invariant: the window is bounded. The service self-stops after
 * [EXTRA_DURATION_MINUTES]; nothing here can run forever.
 */
class MonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val cardType = intent?.getStringExtra(EXTRA_CARD_TYPE)
        val cardJson = intent?.getStringExtra(EXTRA_CARD_JSON)
        if (cardType == null || cardJson == null) {
            Log.w(TAG, "start without card extras; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        val durationMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, DEFAULT_DURATION_MINUTES)
        val card = CardConfig(
            type = cardType,
            raw = json.parseToJsonElement(cardJson) as JsonObject,
        )

        val placeholder = placeholderNotification(card, durationMinutes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                placeholder,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, placeholder)
        }

        loopJob?.cancel()
        loopJob = scope.launch { runMonitoringLoop(card, durationMinutes) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runMonitoringLoop(card: CardConfig, durationMinutes: Int) {
        val deadline = System.currentTimeMillis() + durationMinutes * 60_000L
        while (scope.isActive && System.currentTimeMillis() < deadline) {
            runCatching { emitTick(card, deadline) }
                .onFailure { Log.w(TAG, "tick failed", it) }
            delay(TICK_INTERVAL_MILLIS)
        }
        stopSelf()
    }

    private suspend fun emitTick(card: CardConfig, deadline: Long) {
        val snapshot = DemoData.snapshot()
        val density = resources.configuration.densityDpi
        val widthPx = dpToPx(NOTIFICATION_WIDTH_DP)
        val heightPx = dpToPx(defaultRegistry().cardHeightDp(card, snapshot))

        val doc = captureCardDocument(
            context = this,
            widthPx = widthPx,
            heightPx = heightPx,
            densityDpi = density,
            registry = defaultRegistry(),
            card = card,
            snapshot = snapshot,
        )

        val remoteViews = rcRemoteViews(doc.bytes)
        val notif = monitoringNotification(card, deadline, remoteViews)

        val nm = NotificationManagerCompat.from(this)
        if (nm.areNotificationsEnabled() && hasPostNotificationPermission()) {
            nm.notify(NOTIFICATION_ID, notif)
        }
        kickPinnedWidgets()
    }

    private fun rcRemoteViews(bytes: ByteArray): RemoteViews {
        // API 35+ — our minSdk is 36, so unconditionally available.
        val instructions = RemoteViews.DrawInstructions.Builder(listOf(bytes)).build()
        return RemoteViews(instructions)
    }

    private fun placeholderNotification(card: CardConfig, durationMinutes: Int): Notification =
        baseBuilder(card)
            .setContentText("Starting $durationMinutes-minute window…")
            .build()

    private fun monitoringNotification(
        card: CardConfig,
        deadline: Long,
        content: RemoteViews,
    ): Notification {
        val minutesLeft = ((deadline - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0L)
        return baseBuilder(card)
            .setContentText("$minutesLeft min left")
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomBigContentView(content)
            .build()
    }

    private fun baseBuilder(card: CardConfig): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Monitoring ${card.type}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent(),
            )

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, MonitoringService::class.java).apply { action = ACTION_STOP }
        return PendingIntent.getService(
            this,
            REQUEST_STOP,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun kickPinnedWidgets() {
        val mgr = getSystemService(AppWidgetManager::class.java) ?: return
        val component = ComponentName(this, TerrazzoWidgetProvider::class.java)
        val ids = mgr.getAppWidgetIds(component)
        if (ids.isEmpty()) return
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
            this.component = component
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        sendBroadcast(intent)
    }

    private fun hasPostNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics,
        ).toInt()

    companion object {
        const val CHANNEL_ID = "terrazzo.monitoring"
        const val EXTRA_CARD_TYPE = "terrazzo.monitor.cardType"
        const val EXTRA_CARD_JSON = "terrazzo.monitor.cardJson"
        const val EXTRA_DURATION_MINUTES = "terrazzo.monitor.durationMinutes"
        const val ACTION_STOP = "ee.schimke.terrazzo.monitor.STOP"

        const val DEFAULT_DURATION_MINUTES = 15

        private const val NOTIFICATION_ID = 0x7e7a
        private const val TICK_INTERVAL_MILLIS = 10_000L
        private const val NOTIFICATION_WIDTH_DP = 320
        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_STOP = 1

        private const val TAG = "MonitoringService"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun start(context: Context, card: CardConfig, durationMinutes: Int = DEFAULT_DURATION_MINUTES) {
            val intent = Intent(context, MonitoringService::class.java).apply {
                putExtra(EXTRA_CARD_TYPE, card.type)
                putExtra(
                    EXTRA_CARD_JSON,
                    Json.encodeToString(JsonObject.serializer(), card.raw),
                )
                putExtra(EXTRA_DURATION_MINUTES, durationMinutes)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitoringService::class.java))
        }
    }
}
