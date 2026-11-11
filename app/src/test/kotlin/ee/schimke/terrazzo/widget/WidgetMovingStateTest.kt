package ee.schimke.terrazzo.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import dev.zacsweers.metro.createGraphFactory
import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.terrazzo.TerrazzoGraphProvider
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.core.mobileapp.MobileAppRegistration
import ee.schimke.terrazzo.core.pin.PinStore
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.LiveHaSession
import ee.schimke.terrazzo.core.session.SessionConnectionStatus
import ee.schimke.terrazzo.core.wearsync.WearSyncManager
import ee.schimke.terrazzo.di.AppGraph
import ee.schimke.terrazzo.monitor.createMonitor
import ee.schimke.terrazzo.terrazzoGraph
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Robolectric coverage for temporary moving states (`closing` → `closed`, `opening` → `open`, …) on
 * launcher and Wear widgets (`docs/architecture/live-widget-updates.md`).
 *
 * Regression for widgets getting stuck on `closing` when:
 * 1. Tapping a cover widget with **Live updates** off sent `call_service` and closed immediately;
 * 2. Backgrounding the app dropped the foreground socket mid-`closing` without releasing the 90 s
 *    push lease or handing off to [WidgetStateWorker];
 * 3. Driving off home Wi-Fi onto cellular dropped [WidgetStateWorker]'s WebSocket mid-movement with
 *    no reconnect or retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = WidgetMovingStateTest.TestApp::class)
class WidgetMovingStateTest {

  class TestApp : Application(), TerrazzoGraphProvider {
    val wearSync = RecordingWearSyncManager()
    override val graph: TerrazzoGraph by lazy {
      createGraphFactory<AppGraph.Factory>()
        .create(applicationContext, createMonitor(applicationContext), wearSync)
    }
  }

  private val app: TestApp
    get() = RuntimeEnvironment.getApplication() as TestApp

  private val context: Context
    get() = app

  private val workManager: WorkManager
    get() = WorkManager.getInstance(context)

  @BeforeTest
  fun setUp() {
    InMemoryAndroidKeyStore.install()
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )
    WidgetStateWorker.resetForTest()
    LiveWidgetUpdates.resetTimingForTest()
    WidgetActionRunner.newSessionFactory = null
    WidgetActionRunner.setAppSession(null)
    app.wearSync.reset()
    runBlocking(Dispatchers.Default) {
      val graph = context.terrazzoGraph()
      graph.widgetStore.retainOnly(emptySet())
      graph.mobileAppStore.clear(BASE_URL)
      graph.tokenVault.clear(BASE_URL)
    }
    shadowOf(app).clearBroadcastIntents()
    shadowOf(Looper.getMainLooper()).idle()
  }

  @AfterTest
  fun tearDown() {
    WidgetStateWorker.resetForTest()
    LiveWidgetUpdates.resetTimingForTest()
    WidgetActionRunner.newSessionFactory = null
    WidgetActionRunner.setAppSession(null)
    app.wearSync.reset()
    shadowOf(Looper.getMainLooper()).idle()
  }

  @Test
  fun tapOnCoverWidgetWithLiveUpdatesOffEnqueuesFollowAndSettlesClosed() =
    runBlocking(Dispatchers.Default) {
      val graph = context.terrazzoGraph()
      // Live updates are off by default: tapping a cover widget must still follow until settled.
      graph.widgetStore.put(
        widgetId = 11,
        baseUrl = BASE_URL,
        card = coverCard,
        liveUpdates = false,
      )
      graph.offlineCache.putLatestStates(BASE_URL, snapshotOf("cover.garage" to "open"))

      val serviceCalls = CopyOnWriteArrayList<Triple<String, String, String?>>()
      val actionSession =
        ControllableHaSession(
          baseUrl = BASE_URL,
          onCallService = { domain, service, entityId ->
            serviceCalls += Triple(domain, service, entityId)
          },
        )
      WidgetActionRunner.newSessionFactory = { actionSession }

      WidgetActionRunner.run(
        context,
        widgetId = 11,
        action = HaAction.CallService("cover", "close_cover", "cover.garage"),
      )

      assertEquals(listOf(Triple("cover", "close_cover", "cover.garage")), serviceCalls.toList())
      val work = workManager.getWorkInfosForUniqueWork(WidgetStateWorker.workName(BASE_URL)).get()
      assertEquals(1, work.size)
      assertEquals(WorkInfo.State.ENQUEUED, work.single().state)
      assertEquals(NetworkType.CONNECTED, work.single().constraints.requiredNetworkType)

      // Now run the enqueued WidgetStateWorker as the garage door transitions closing -> closed.
      WidgetStateWorker.settleMs = 60L
      WidgetStateWorker.maxWindowMs = 5_000L
      LiveWidgetUpdates.checkMs = 15L
      LiveWidgetUpdates.visibleRedrawMs = 10L

      val followSession =
        ControllableHaSession(
          baseUrl = BASE_URL,
          initialStates = snapshotOf("cover.garage" to "closing"),
        )
      WidgetActionRunner.newSessionFactory = { followSession }
      shadowOf(app).clearBroadcastIntents()
      app.wearSync.pushedSnapshots.clear()

      val workerJob = async {
        TestListenableWorkerBuilder<WidgetStateWorker>(context)
          .setInputData(workDataOf(WidgetStateWorker.KEY_BASE_URL to BASE_URL))
          .build()
          .doWork()
      }
      try {
        withTimeout(2_000L) {
          while (
            graph.offlineCache.latestSnapshot(BASE_URL)?.states?.get("cover.garage")?.state !=
              "closing"
          ) {
            delay(10L)
          }
        }
        followSession.emitStates(snapshotOf("cover.garage" to "closed"))

        val result = withTimeout(2_000L) { workerJob.await() }
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(
          "closed",
          graph.offlineCache.latestSnapshot(BASE_URL)?.states?.get("cover.garage")?.state,
        )
        assertTrue(widgetBroadcastIds().count { 11 in it } >= 2, "redrew both closing and closed")
        assertEquals(
          listOf("closing", "closed"),
          app.wearSync.pushedSnapshots.mapNotNull { it.states["cover.garage"]?.state },
        )
      } finally {
        workerJob.cancel()
      }
    }

  @Test
  fun plainOnOffLightToggleUpdatesOptimisticallyWithoutEnqueuingFollowWorker() =
    runBlocking(Dispatchers.Default) {
      val graph = context.terrazzoGraph()
      val lightCard =
        CardConfig("tile", JsonObject(mapOf("entity" to JsonPrimitive("light.porch"))))
      graph.widgetStore.put(
        widgetId = 12,
        baseUrl = BASE_URL,
        card = lightCard,
        liveUpdates = false,
      )
      graph.offlineCache.putLatestStates(BASE_URL, snapshotOf("light.porch" to "off"))
      WidgetActionRunner.newSessionFactory = { ControllableHaSession(baseUrl = BASE_URL) }

      WidgetActionRunner.run(context, widgetId = 12, action = HaAction.Toggle("light.porch"))

      assertEquals(
        "on",
        graph.offlineCache.latestSnapshot(BASE_URL)?.states?.get("light.porch")?.state,
      )
      assertTrue(
        workManager.getWorkInfosForUniqueWork(WidgetStateWorker.workName(BASE_URL)).get().isEmpty(),
        "instant on/off toggles should not hold a background follow session open",
      )
    }

  @Test
  fun workerStaysAwakePastSettleWindowWhileGarageRemainsClosingAndIgnoresQuietSensorNoise() =
    runBlocking(Dispatchers.Default) {
      val graph = context.terrazzoGraph()
      val multiCard =
        CardConfig(
          "entities",
          buildJsonObject {
            put(
              "entities",
              buildJsonArray {
                add(JsonPrimitive("cover.garage"))
                add(JsonPrimitive("sensor.garage_power"))
              },
            )
          },
        )
      graph.widgetStore.put(
        widgetId = 13,
        baseUrl = BASE_URL,
        card = multiCard,
        liveUpdates = false,
      )
      WidgetStateWorker.settleMs = 70L
      WidgetStateWorker.maxWindowMs = 5_000L
      LiveWidgetUpdates.checkMs = 15L
      LiveWidgetUpdates.visibleRedrawMs = 10L
      LiveWidgetUpdates.quietRedrawMs = 60_000L

      val session =
        ControllableHaSession(
          baseUrl = BASE_URL,
          initialStates = snapshotOf("cover.garage" to "closing", "sensor.garage_power" to "140.0"),
        )
      WidgetActionRunner.newSessionFactory = { session }

      val workerJob = async {
        TestListenableWorkerBuilder<WidgetStateWorker>(context)
          .setInputData(workDataOf(WidgetStateWorker.KEY_BASE_URL to BASE_URL))
          .build()
          .doWork()
      }
      try {
        // Wait well past settleMs (70 ms) while cover.garage stays "closing": must NOT exit early.
        delay(220L)
        assertTrue(workerJob.isActive, "worker exited while cover.garage was still closing")

        // Flip the cover to closed, wait for it to draw, then emit quiet numeric power ticks:
        // noisy numbers must not reset changedAt, and the trailing quiet state must still flush.
        session.emitStates(snapshotOf("cover.garage" to "closed", "sensor.garage_power" to "140.0"))
        withTimeout(2_000L) {
          while (
            graph.offlineCache.latestSnapshot(BASE_URL)?.states?.get("cover.garage")?.state !=
              "closed"
          ) {
            delay(10L)
          }
        }
        session.emitStates(snapshotOf("cover.garage" to "closed", "sensor.garage_power" to "5.2"))

        val result = withTimeout(2_000L) { workerJob.await() }
        assertEquals(ListenableWorker.Result.success(), result)
        val finalStates = graph.offlineCache.latestSnapshot(BASE_URL)?.states.orEmpty()
        assertEquals("closed", finalStates["cover.garage"]?.state)
        assertEquals(
          "5.2",
          finalStates["sensor.garage_power"]?.state,
          "flushed trailing quiet tick",
        )
      } finally {
        workerJob.cancel()
      }
    }

  @Test
  fun wifiToCellularSocketDropMidClosingReconnectsReacquiresLeaseAndCatchesClosed() =
    runBlocking(Dispatchers.Default) {
      val port = ServerSocket(0).use { it.localPort }
      val liveUrl = "http://127.0.0.1:$port"
      val graph = context.terrazzoGraph()
      graph.widgetStore.put(widgetId = 14, baseUrl = liveUrl, card = coverCard, liveUpdates = true)
      graph.mobileAppStore.put(
        liveUrl,
        MobileAppRegistration(
          deviceId = "pixel-9",
          deviceName = "Pixel",
          webhookId = "hook-garage",
        ),
      )

      WidgetStateWorker.settleMs = 80L
      WidgetStateWorker.maxWindowMs = 10_000L
      LiveWidgetUpdates.checkMs = 20L
      LiveWidgetUpdates.visibleRedrawMs = 10L
      LiveWidgetUpdates.reconnectInitialMs = 2_000L
      LiveWidgetUpdates.reconnectMaxMs = 5_000L

      val firstServerLeases = CopyOnWriteArrayList<Int>()
      val firstLeaseReady = CompletableDeferred<Unit>()
      var server1: EmbeddedServer<*, *>? = null
      var server2: EmbeddedServer<*, *>? = null
      var workerJob: kotlinx.coroutines.Deferred<ListenableWorker.Result>? = null
      try {
        server1 =
          startHaWsServer(
            port = port,
            currentGarageState = { "closing" },
            onWatchLease = { leaseSeconds ->
              firstServerLeases += leaseSeconds
              firstLeaseReady.complete(Unit)
            },
          )

        WidgetActionRunner.newSessionFactory = { url ->
          LiveHaSession(baseUrl = url, accessToken = "test-token")
        }

        workerJob = async {
          TestListenableWorkerBuilder<WidgetStateWorker>(context)
            .setInputData(workDataOf(WidgetStateWorker.KEY_BASE_URL to liveUrl))
            .build()
            .doWork()
        }

        withTimeout(3_000L) { firstLeaseReady.await() }
        withTimeout(3_000L) {
          while (
            graph.offlineCache.latestSnapshot(liveUrl)?.states?.get("cover.garage")?.state !=
              "closing"
          ) {
            delay(15L)
          }
        }
        assertEquals(listOf(90), firstServerLeases.toList())

        // Simulate driving away from home Wi-Fi while the garage door is still closing:
        // kill the first socket server, spin up the cellular-reachable server on the same port,
        // and notify ConnectivityManager so LanConnectionPolicy.networkChanges wakes the
        // reconnector
        // immediately without waiting out backoff.
        val garageStateOnServer2 = AtomicReference("closing")
        val secondServerLeases = CopyOnWriteArrayList<Int>()
        val secondSubscribed = CompletableDeferred<Pair<WebSocketSession, Int>>()

        server1.stop(gracePeriodMillis = 20, timeoutMillis = 300)
        server1 = null

        server2 =
          startHaWsServer(
            port = port,
            currentGarageState = garageStateOnServer2::get,
            onWatchLease = { secondServerLeases += it },
            onStateSubscribed = { ws, subId -> secondSubscribed.complete(ws to subId) },
          )

        // Wait until LiveHaSession observes the dropped socket, then signal cellular availability.
        delay(80L)
        notifyCellularAvailable()

        val (ws2, subId) = withTimeout(4_000L) { secondSubscribed.await() }
        // Once reconnected over cellular, the garage door finishes closing.
        garageStateOnServer2.set("closed")
        ws2.send(
          Frame.Text(
            buildJsonObject {
              put("id", subId)
              put("type", "event")
              put(
                "event",
                buildJsonObject {
                  put(
                    "data",
                    buildJsonObject {
                      put("entity_id", "cover.garage")
                      put(
                        "new_state",
                        buildJsonObject {
                          put("entity_id", "cover.garage")
                          put("state", "closed")
                        },
                      )
                    },
                  )
                },
              )
            }
              .toString(),
          ),
        )

        val result = withTimeout(4_000L) { workerJob.await() }
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(
          "closed",
          graph.offlineCache.latestSnapshot(liveUrl)?.states?.get("cover.garage")?.state,
        )
        assertTrue(90 in secondServerLeases, "re-acquired 90 s lease after reconnect")
        assertEquals(0, secondServerLeases.last(), "released lease (0 s) on window exit")
      } finally {
        workerJob?.cancel()
        runCatching { server1?.stop(gracePeriodMillis = 20, timeoutMillis = 200) }
        runCatching { server2?.stop(gracePeriodMillis = 20, timeoutMillis = 200) }
        graph.mobileAppStore.clear(liveUrl)
      }
    }

  @Test
  fun parkAndHandOffReleasesLeaseBeforeDisconnectAndEnqueuesOnlyWhenMoving() =
    runBlocking(Dispatchers.Default) {
      val graph = context.terrazzoGraph()
      // Cover both launcher widgets and watch-only widgets via wearSyncManager.watchedEntities.
      app.wearSync.watched = setOf("lock.front_door")
      graph.widgetStore.put(widgetId = 15, baseUrl = BASE_URL, card = coverCard, liveUpdates = true)
      graph.mobileAppStore.put(
        BASE_URL,
        MobileAppRegistration(
          deviceId = "pixel-9",
          deviceName = "Pixel",
          webhookId = "hook-garage",
        ),
      )

      val events = CopyOnWriteArrayList<String>()
      val closingSession =
        ControllableHaSession(
          baseUrl = BASE_URL,
          initialStatus = SessionConnectionStatus.Connected,
          initialStates = snapshotOf("cover.garage" to "closed", "lock.front_door" to "locking"),
          onWatchStates = { hook, ids, lease ->
            events +=
              "watch:$hook:${ids.sorted().joinToString(",")}:$lease:${connectionStatus.value}"
            lease
          },
          onDisconnect = { events += "disconnect" },
        )

      LiveWidgetUpdates.parkAndHandOff(context, closingSession)

      // Lease release MUST run while connectionStatus is still Connected, strictly before
      // disconnect, and moving watch widget entities also hand off to WidgetStateWorker.
      assertEquals(
        listOf("watch:hook-garage:cover.garage,lock.front_door:0:Connected", "disconnect"),
        events.toList(),
      )
      assertEquals(
        "locking",
        graph.offlineCache.latestSnapshot(BASE_URL)?.states?.get("lock.front_door")?.state,
      )
      val handedOff =
        workManager.getWorkInfosForUniqueWork(WidgetStateWorker.workName(BASE_URL)).get()
      assertEquals(1, handedOff.size)
      assertEquals(WorkInfo.State.ENQUEUED, handedOff.single().state)

      // Now test leaving the foreground when both launcher and watch entities have settled.
      workManager.cancelUniqueWork(WidgetStateWorker.workName(BASE_URL)).result.get()
      workManager.pruneWork().result.get()
      events.clear()

      val settledSession =
        ControllableHaSession(
          baseUrl = BASE_URL,
          initialStatus = SessionConnectionStatus.Connected,
          initialStates = snapshotOf("cover.garage" to "closed", "lock.front_door" to "locked"),
          onWatchStates = { hook, _, lease ->
            events += "watch:$hook:$lease:${connectionStatus.value}"
            lease
          },
          onDisconnect = { events += "disconnect" },
        )

      LiveWidgetUpdates.parkAndHandOff(context, settledSession)

      assertEquals(listOf("watch:hook-garage:0:Connected", "disconnect"), events.toList())
      assertTrue(
        workManager.getWorkInfosForUniqueWork(WidgetStateWorker.workName(BASE_URL)).get().isEmpty(),
        "settled entities must not spawn a background follow job on exit",
      )
    }

  @Test
  fun transientFailureAndUnsettledWindowRetryUpToMaxAttemptsThenCooldownUntilReset() =
    runBlocking(Dispatchers.Default) {
      val graph = context.terrazzoGraph()
      graph.widgetStore.put(
        widgetId = 16,
        baseUrl = BASE_URL,
        card = coverCard,
        liveUpdates = false,
      )

      // 1. Offline / connect failure retries.
      WidgetActionRunner.newSessionFactory = {
        ControllableHaSession(
          baseUrl = BASE_URL,
          onConnect = { throw IOException("Cellular link not ready") },
        )
      }
      val offlineResult =
        TestListenableWorkerBuilder<WidgetStateWorker>(context)
          .setInputData(workDataOf(WidgetStateWorker.KEY_BASE_URL to BASE_URL))
          .setRunAttemptCount(0)
          .build()
          .doWork()
      assertEquals(ListenableWorker.Result.retry(), offlineResult)

      // 2. Window expiring while cover.garage is still "closing" retries before MAX_ATTEMPTS.
      var nowMs = 1_000L
      WidgetStateWorker.clock = { nowMs }
      LiveWidgetUpdates.clock = { nowMs }
      WidgetStateWorker.maxWindowMs = 80L
      WidgetStateWorker.settleMs = 40L
      LiveWidgetUpdates.checkMs = 15L

      val stuckClosing =
        ControllableHaSession(
          baseUrl = BASE_URL,
          initialStates = snapshotOf("cover.garage" to "closing"),
        )
      WidgetActionRunner.newSessionFactory = { stuckClosing }

      val attempt1Job = async {
        TestListenableWorkerBuilder<WidgetStateWorker>(context)
          .setInputData(workDataOf(WidgetStateWorker.KEY_BASE_URL to BASE_URL))
          .setRunAttemptCount(1)
          .build()
          .doWork()
      }
      try {
        withTimeout(2_000L) {
          while (!attempt1Job.isCompleted) {
            delay(10L)
            nowMs += 30L
          }
        }
        assertEquals(ListenableWorker.Result.retry(), attempt1Job.await())
      } finally {
        attempt1Job.cancel()
      }

      // 3. At MAX_ATTEMPTS on a broken sensor stuck in "closing", worker bounds retries with
      // success and starts the cooldown so passive widget renders don't spin forever.
      val exhaustedJob = async {
        TestListenableWorkerBuilder<WidgetStateWorker>(context)
          .setInputData(workDataOf(WidgetStateWorker.KEY_BASE_URL to BASE_URL))
          .setRunAttemptCount(WidgetStateWorker.MAX_ATTEMPTS)
          .build()
          .doWork()
      }
      try {
        withTimeout(2_000L) {
          while (!exhaustedJob.isCompleted) {
            delay(10L)
            nowMs += 30L
          }
        }
        assertEquals(ListenableWorker.Result.success(), exhaustedJob.await())
      } finally {
        exhaustedJob.cancel()
      }

      // Passive ensureFollowing (resetCooldown = false) is suppressed during cooldown.
      WidgetStateWorker.ensureFollowing(context, BASE_URL, resetCooldown = false)
      assertTrue(
        workManager.getWorkInfosForUniqueWork(WidgetStateWorker.workName(BASE_URL)).get().isEmpty(),
        "passive render must respect give-up cooldown",
      )

      // Explicit user action (resetCooldown = true) overrides cooldown at once.
      WidgetStateWorker.ensureFollowing(context, BASE_URL, resetCooldown = true)
      val firstEnqueued =
        workManager.getWorkInfosForUniqueWork(WidgetStateWorker.workName(BASE_URL)).get().single()
      assertEquals(WorkInfo.State.ENQUEUED, firstEnqueued.state)

      // And an FCM push wake (enqueue) replaces an existing window under the same baseUrl work
      // name.
      graph.mobileAppStore.put(
        BASE_URL,
        MobileAppRegistration(deviceId = "pixel-9", deviceName = "Pixel", webhookId = "hook-wake"),
      )
      WidgetStateWorker.enqueue(context, "hook-wake")
      val replaced =
        workManager.getWorkInfosForUniqueWork(WidgetStateWorker.workName(BASE_URL)).get().single()
      assertEquals(WorkInfo.State.ENQUEUED, replaced.state)
      assertNotEquals(firstEnqueued.id, replaced.id)
    }

  @Test
  fun providerRenderAndRoutineRefreshEnqueueKeepFollowWhenMovingAndAppClosed() =
    runBlocking(Dispatchers.Default) {
      val graph = context.terrazzoGraph()
      graph.widgetStore.put(
        widgetId = 17,
        baseUrl = BASE_URL,
        card = coverCard,
        liveUpdates = false,
      )
      val entry = graph.widgetStore.get(17)!!
      val openingSnapshot = snapshotOf("cover.garage" to "opening")

      // 1. TerrazzoWidgetProvider noticing a moving entity enqueues with ExistingWorkPolicy.KEEP.
      val provider = TerrazzoWidgetProvider()
      provider.followIfMoving(context, entry, openingSnapshot)
      val first =
        workManager.getWorkInfosForUniqueWork(WidgetStateWorker.workName(BASE_URL)).get().single()
      assertEquals(WorkInfo.State.ENQUEUED, first.state)

      provider.followIfMoving(context, entry, openingSnapshot)
      val second =
        workManager.getWorkInfosForUniqueWork(WidgetStateWorker.workName(BASE_URL)).get().single()
      assertEquals(first.id, second.id, "KEEP must preserve the already-enqueued window")

      workManager.cancelUniqueWork(WidgetStateWorker.workName(BASE_URL)).result.get()
      workManager.pruneWork().result.get()

      // 2. While the foreground app session is connected, neither provider nor routine worker
      // spawns a background follow job.
      val appSession =
        ControllableHaSession(
          baseUrl = BASE_URL,
          initialStatus = SessionConnectionStatus.Connected,
          initialStates = openingSnapshot,
        )
      WidgetActionRunner.setAppSession(appSession)
      provider.followIfMoving(context, entry, openingSnapshot)
      assertTrue(
        workManager.getWorkInfosForUniqueWork(WidgetStateWorker.workName(BASE_URL)).get().isEmpty(),
      )

      // 3. With the app closed, WidgetRoutineRefreshWorker fetching a moving snapshot enqueues
      // follow.
      WidgetActionRunner.setAppSession(null)
      WidgetActionRunner.newSessionFactory = {
        ControllableHaSession(baseUrl = BASE_URL, snapshotOnLoad = openingSnapshot)
      }

      val refreshResult =
        TestListenableWorkerBuilder<WidgetRoutineRefreshWorker>(context).build().doWork()
      assertEquals(ListenableWorker.Result.success(), refreshResult)
      assertEquals(
        "opening",
        graph.offlineCache.latestSnapshot(BASE_URL)?.states?.get("cover.garage")?.state,
      )
      val routineEnqueued =
        workManager.getWorkInfosForUniqueWork(WidgetStateWorker.workName(BASE_URL)).get().single()
      assertEquals(WorkInfo.State.ENQUEUED, routineEnqueued.state)
    }

  private fun widgetBroadcastIds(): List<List<Int>> =
    shadowOf(app)
      .broadcastIntents
      .filter { it.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE }
      .map { it.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)?.toList().orEmpty() }

  private fun notifyCellularAvailable() {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = ShadowNetwork.newInstance(99)
    val caps =
      ShadowNetworkCapabilities.newInstance().also {
        shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
      }
    shadowOf(cm).setNetworkCapabilities(network, caps)
    shadowOf(cm).networkCallbacks.forEach { callback ->
      callback.onAvailable(network)
      callback.onCapabilitiesChanged(network, caps)
    }
  }

  private fun startHaWsServer(
    port: Int,
    currentGarageState: () -> String,
    onWatchLease: (Int) -> Unit = {},
    onStateSubscribed: (WebSocketSession, Int) -> Unit = { _, _ -> },
  ): EmbeddedServer<*, *> =
    embeddedServer(CIO, port = port, host = "127.0.0.1") {
        install(WebSockets)
        routing {
          webSocket("/api/websocket") {
            send(Frame.Text("""{"type":"auth_required","ha_version":"test"}"""))
            for (frame in incoming) {
              if (frame !is Frame.Text) continue
              val msg = wsJson.parseToJsonElement(frame.readText()).jsonObject
              val type = msg["type"]?.jsonPrimitive?.content ?: continue
              if (type == "auth") {
                send(Frame.Text("""{"type":"auth_ok"}"""))
                continue
              }
              val id = msg["id"]?.jsonPrimitive?.intOrNull ?: continue
              val reply: JsonObject =
                when (type) {
                  "get_states" ->
                    wsResult(
                      id,
                      buildJsonArray {
                        add(
                          buildJsonObject {
                            put("entity_id", "cover.garage")
                            put("state", currentGarageState())
                          },
                        )
                      },
                    )
                  "subscribe_events" -> {
                    val eventType = msg["event_type"]?.jsonPrimitive?.content
                    val ack = wsResult(id, JsonObject(emptyMap()))
                    send(Frame.Text(ack.toString()))
                    if (eventType == "state_changed") {
                      onStateSubscribed(this, id)
                    }
                    continue
                  }
                  "terrazzo/push/watch" -> {
                    val lease = msg["lease_seconds"]?.jsonPrimitive?.intOrNull ?: 0
                    val entities =
                      msg["entity_ids"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                    assertEquals(listOf("cover.garage"), entities)
                    onWatchLease(lease)
                    wsResult(id, buildJsonObject { put("lease_seconds", lease) })
                  }
                  "get_config" ->
                    wsResult(
                      id,
                      buildJsonObject {
                        put("version", "test")
                        put("unit_system", buildJsonObject { put("temperature", "°C") })
                      },
                    )
                  "config/device_registry/list",
                  "config/entity_registry/list",
                  "config/area_registry/list",
                  "persistent_notification/get" -> wsResult(id, buildJsonArray {})
                  else -> wsResult(id, JsonObject(emptyMap()))
                }
              send(Frame.Text(reply.toString()))
            }
          }
        }
      }
      .start(wait = false)

  private fun wsResult(id: Int, value: kotlinx.serialization.json.JsonElement) = buildJsonObject {
    put("id", id)
    put("type", "result")
    put("success", true)
    put("result", value)
  }

  class RecordingWearSyncManager : WearSyncManager {
    @Volatile var watched: Set<String> = emptySet()
    val pushedSnapshots = CopyOnWriteArrayList<HaSnapshot>()
    override val streamActive: StateFlow<Boolean> = MutableStateFlow(false)
    override val wearableAvailable: StateFlow<Boolean> = MutableStateFlow(false)

    override fun setSession(session: HaSession?) = Unit

    override suspend fun watchedEntities(): Set<String> = watched

    override suspend fun pushValues(snapshot: HaSnapshot) {
      pushedSnapshots += snapshot
    }

    override fun start(
      scope: CoroutineScope,
      prefs: PreferencesStore,
      pinStore: PinStore,
      inputEditing: StateFlow<Boolean>,
    ) = Unit

    fun reset() {
      watched = emptySet()
      pushedSnapshots.clear()
    }
  }

  private class ControllableHaSession(
    override val baseUrl: String,
    initialStatus: SessionConnectionStatus = SessionConnectionStatus.Connecting,
    initialStates: HaSnapshot? = null,
    private val snapshotOnLoad: HaSnapshot = initialStates ?: HaSnapshot(),
    private val onConnect: suspend ControllableHaSession.() -> Unit = {},
    private val onDisconnect: suspend ControllableHaSession.() -> Unit = {},
    private val onCallService: suspend (String, String, String?) -> Unit = { _, _, _ -> },
    private val onWatchStates:
      suspend ControllableHaSession.(String, Collection<String>, Int) -> Int? =
      { _, _, lease ->
        lease
      },
  ) : HaSession {
    private val _status = MutableStateFlow(initialStatus)
    override val connectionStatus: StateFlow<SessionConnectionStatus> = _status

    private val _liveStates = MutableStateFlow(initialStates)
    override val liveStates: StateFlow<HaSnapshot?> = _liveStates

    fun emitStates(snapshot: HaSnapshot?) {
      _liveStates.value = snapshot
    }

    override suspend fun connect() {
      onConnect()
      _status.value = SessionConnectionStatus.Connected
    }

    override suspend fun disconnect() {
      onDisconnect()
      _status.value = SessionConnectionStatus.Disconnected
    }

    override suspend fun listDashboards(): List<DashboardSummary> = emptyList()

    override suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot> =
      Dashboard() to snapshotOnLoad

    override suspend fun loadSnapshot(): HaSnapshot = snapshotOnLoad

    override suspend fun callService(
      domain: String,
      service: String,
      entityId: String?,
      serviceData: JsonObject,
    ) {
      onCallService(domain, service, entityId)
    }

    override suspend fun watchStates(
      webhookId: String,
      entityIds: Collection<String>,
      leaseSeconds: Int,
    ): Int? = onWatchStates(webhookId, entityIds, leaseSeconds)

    override suspend fun close() {
      _status.value = SessionConnectionStatus.Failed
    }
  }

  /**
   * Minimal in-memory JCA `AndroidKeyStore` provider so
   * [ee.schimke.terrazzo.core.mobileapp.MobileAppStore] and
   * [ee.schimke.terrazzo.core.auth.TokenVault] run real AES-GCM encryption under Robolectric on the
   * JVM.
   */
  private object InMemoryAndroidKeyStore {
    val keys = ConcurrentHashMap<String, SecretKey>()

    fun install() {
      keys.clear()
      if (Security.getProvider("AndroidKeyStore") != null) return
      Security.addProvider(
        object : Provider("AndroidKeyStore", 1.0, "In-memory AndroidKeyStore for Robolectric") {
          init {
            put("KeyStore.AndroidKeyStore", StoreSpi::class.java.name)
            put("KeyGenerator.AES", AesGeneratorSpi::class.java.name)
          }
        },
      )
    }

    class StoreSpi : KeyStoreSpi() {
      override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]

      override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

      override fun engineGetCertificate(alias: String): Certificate? = null

      override fun engineGetCreationDate(alias: String): Date? = keys[alias]?.let { Date() }

      override fun engineSetKeyEntry(
        alias: String,
        key: Key,
        password: CharArray?,
        chain: Array<Certificate>?,
      ) {
        keys[alias] = key as SecretKey
      }

      override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<Certificate>?) =
        Unit

      override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit

      override fun engineDeleteEntry(alias: String) {
        keys.remove(alias)
      }

      override fun engineAliases(): Enumeration<String> = keys.keys()

      override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)

      override fun engineSize(): Int = keys.size

      override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)

      override fun engineIsCertificateEntry(alias: String): Boolean = false

      override fun engineGetCertificateAlias(cert: Certificate): String? = null

      override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit

      override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    class AesGeneratorSpi : KeyGeneratorSpi() {
      private var alias: String = "default"
      private var keyBits: Int = 256
      private var random: SecureRandom = SecureRandom()

      override fun engineInit(random: SecureRandom) {
        this.random = random
      }

      override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
        if (random != null) this.random = random
        if (params is KeyGenParameterSpec) {
          alias = params.keystoreAlias
          if (params.keySize > 0) keyBits = params.keySize
        }
      }

      override fun engineInit(keysize: Int, random: SecureRandom?) {
        if (keysize > 0) keyBits = keysize
        if (random != null) this.random = random
      }

      override fun engineGenerateKey(): SecretKey {
        val bytes = ByteArray(keyBits / 8).also(random::nextBytes)
        return SecretKeySpec(bytes, "AES").also { keys[alias] = it }
      }
    }
  }

  private companion object {
    const val BASE_URL = "https://ha.example"
    val wsJson = Json { ignoreUnknownKeys = true }
    val coverCard = CardConfig("tile", JsonObject(mapOf("entity" to JsonPrimitive("cover.garage"))))

    fun snapshotOf(vararg pairs: Pair<String, String>) =
      HaSnapshot(
        states =
          pairs.associate { (entityId, state) ->
            entityId to EntityState(entityId = entityId, state = state)
          },
      )
  }
}
