package ee.schimke.terrazzo.quest

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ee.schimke.ha.client.HaClient
import ee.schimke.ha.client.HaConfig
import ee.schimke.ha.client.stateSnapshots
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.room.DashboardImportance
import ee.schimke.ha.room.dashboardImportance
import ee.schimke.terrazzo.room.RoomPin
import ee.schimke.terrazzo.room.RoomState
import ee.schimke.terrazzo.room.decodeRoomPins
import ee.schimke.terrazzo.room.encodeRoomPins
import ee.schimke.terrazzo.room.roomState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Quest's authenticated room state. It owns the Home Assistant WebSocket because the shared session
 * layer is API-35-only; Quest remains installable on Horizon OS API 34.
 */
class QuestRoomModel(context: Context, private val prefs: SharedPreferences) {
  private val scope = MainScope()
  private val auth = QuestAuth(context)
  private val tokenStore = QuestTokenStore(context)
  private var client: HaClient? = null
  private var baseUrl: String? = null
  private var pendingSignInUrl: String? = null
  private var connectionGeneration = 0

  var snapshot by mutableStateOf<HaSnapshot?>(null)
    private set

  private var importance by mutableStateOf(DashboardImportance.Empty)

  var connection by mutableStateOf<QuestConnection>(QuestConnection.SignedOut)
    private set

  var picked by mutableStateOf(prefs.getString(PICKED, null))
    private set

  var tracker by mutableStateOf(prefs.getString(TRACKER, null))
    private set

  var nearestPin by mutableStateOf<String?>(null)

  var pins by mutableStateOf(emptyList<RoomPin>())
    private set

  /** Null until Home Assistant's initial state and area registries arrive. */
  val state: RoomState?
    get() = snapshot?.let { roomState(it, importance, picked, tracker, nearestPin) }

  /** Restore a prior OAuth sign-in, minting a fresh short-lived access token first. */
  fun restore() {
    val credential = tokenStore.load() ?: return
    val generation = ++connectionGeneration
    scope.launch {
      connect(
        credential.baseUrl,
        accessToken = null,
        refreshToken = credential.refreshToken,
        generation = generation,
      )
    }
  }

  fun authorizationIntent(url: String): Intent? {
    val normalized = url.trim().removeSuffix("/")
    pendingSignInUrl = normalized
    return try {
      connection = QuestConnection.SigningIn
      auth.authorizationIntent(normalized)
    } catch (e: IllegalArgumentException) {
      pendingSignInUrl = null
      connection = QuestConnection.Error(e.message ?: "Enter a Home Assistant URL")
      null
    }
  }

  fun authorizationResult(data: Intent?) {
    scope.launch {
      try {
        val tokens = auth.exchangeResult(data)
        val url = pendingSignInUrl ?: error("No Home Assistant URL was pending")
        connect(url, tokens.accessToken, tokens.refreshToken, ++connectionGeneration)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        connection = QuestConnection.Error(e.message ?: "Home Assistant sign-in failed")
      } finally {
        pendingSignInUrl = null
      }
    }
  }

  fun signOut() {
    ++connectionGeneration
    val closing = client
    client = null
    baseUrl = null
    snapshot = null
    importance = DashboardImportance.Empty
    pins = emptyList()
    connection = QuestConnection.SignedOut
    scope.launch {
      closing?.close()
      tokenStore.clear()
    }
  }

  fun pick(areaId: String?) {
    picked = areaId
    prefs.edit().putString(PICKED, areaId).apply()
  }

  fun follow(entityId: String?) {
    tracker = entityId
    prefs.edit().putString(TRACKER, entityId).apply()
  }

  /**
   * The server remains authoritative; the state_changed subscription redraws after its response.
   */
  fun toggle(entityId: String) {
    val entity = snapshot?.states?.get(entityId) ?: return
    val domain = entityId.substringBefore('.', missingDelimiterValue = "")
    if (domain.isEmpty()) return
    scope.launch {
      try {
        client?.callService(domain, if (entity.state == "on") "turn_off" else "turn_on", entityId)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // Keep the room visible: Home Assistant remains authoritative over the subscribed state.
      }
    }
  }

  fun pinned(anchorId: String, entityId: String) {
    val url = baseUrl ?: return
    savePins(pins + RoomPin(anchorId, url, entityId))
  }

  fun unpinned(pin: RoomPin) = savePins(pins.filter { it.anchorId != pin.anchorId })

  private fun savePins(updated: List<RoomPin>) {
    val activeUrl = baseUrl ?: return
    pins = updated
    // Pins belonging to other HA instances survive a sign-out or instance switch.
    val others = decodeRoomPins(prefs.getString(PINS, null)).filter { it.baseUrl != activeUrl }
    prefs.edit().putString(PINS, encodeRoomPins(others + updated)).apply()
  }

  private suspend fun connect(
    rawBaseUrl: String,
    accessToken: String?,
    refreshToken: String?,
    generation: Int,
  ) {
    connection = QuestConnection.Connecting
    val normalized = rawBaseUrl.trim().removeSuffix("/")
    var opened: HaClient? = null
    try {
      val refreshed =
        if (accessToken == null) refreshToken?.let { auth.refresh(normalized, it) } else null
      val token =
        accessToken ?: refreshed?.accessToken ?: error("Home Assistant returned no access token")
      (refreshed?.refreshToken ?: refreshToken)?.let { tokenStore.save(normalized, it) }
      client?.close()
      val connected = HaClient(HaConfig(normalized, token))
      opened = connected
      connected.connect()
      client = connected
      baseUrl = normalized
      pins = decodeRoomPins(prefs.getString(PINS, null)).filter { it.baseUrl == normalized }
      importance =
        runCatching { dashboardImportance(connected.fetchDashboard()) }
          .getOrDefault(DashboardImportance.Empty)
      stateSnapshots(connected.subscribeEvents("state_changed")) {
          connected
            .snapshotWithDevices()
            .copy(areas = runCatching { connected.fetchAreas() }.getOrDefault(emptyMap()))
        }
        .collect {
          snapshot = it
          if (generation == connectionGeneration) connection = QuestConnection.Connected
        }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      opened?.close()
      if (generation == connectionGeneration) {
        if (client === opened) client = null
        connection = QuestConnection.Error(e.message ?: "Could not connect to Home Assistant")
      }
    }
  }

  fun close() {
    scope.cancel()
    auth.close()
  }

  private companion object {
    const val PICKED = "room_picked"
    const val TRACKER = "room_tracker"
    const val PINS = "room_pins"
  }
}

sealed interface QuestConnection {
  data object SignedOut : QuestConnection

  data object SigningIn : QuestConnection

  data object Connecting : QuestConnection

  data object Connected : QuestConnection

  data class Error(val message: String) : QuestConnection
}
