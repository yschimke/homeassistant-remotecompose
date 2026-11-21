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
import ee.schimke.terrazzo.core.auth.HaAuthService
import ee.schimke.terrazzo.core.auth.RestoreKeyEscrow
import ee.schimke.terrazzo.core.auth.TokenVault
import ee.schimke.terrazzo.core.network.HttpEngineFactory
import ee.schimke.terrazzo.core.network.LanConnectionPolicy
import ee.schimke.terrazzo.core.network.RemoteUrlStore
import ee.schimke.terrazzo.room.RoomPin
import ee.schimke.terrazzo.room.RoomState
import ee.schimke.terrazzo.room.decodeRoomPins
import ee.schimke.terrazzo.room.encodeRoomPins
import ee.schimke.terrazzo.room.roomState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse

/**
 * Quest's authenticated room state. Sign-in and the refresh token are `:terrazzo-core`'s, as on the
 * phone: [HaAuthService] for the OAuth code and refresh grants, [TokenVault] for the
 * Keystore-sealed refresh token (with no Credential Manager escrow: Horizon OS has no Play
 * services). The WebSocket stays here, because the room view also reads areas and dashboard
 * importance, which the shared session doesn't carry.
 */
class QuestRoomModel(context: Context, private val prefs: SharedPreferences) {
  private val scope = MainScope()
  private val auth =
    HaAuthService(
      context,
      HttpEngineFactory(LanConnectionPolicy(context), RemoteUrlStore(context)),
    )
  private val vault = TokenVault(context, RestoreKeyEscrow.Disabled)
  private var client: HaClient? = null
  private var baseUrl: String? = null
  private var pendingSignInUrl: String? = null
  private var pendingSignInGeneration: Int? = null
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

  /** Explicit opt-in for physical home controls; new Quest installs start read-only. */
  var controlsLocked by mutableStateOf(prefs.getBoolean(CONTROLS_LOCKED, true))
    private set

  var nearestPin by mutableStateOf<String?>(null)

  var pins by mutableStateOf(emptyList<RoomPin>())
    private set

  /** Null until Home Assistant's initial state and area registries arrive. */
  val state: RoomState?
    get() = snapshot?.let { roomState(it, importance, picked, tracker, nearestPin) }

  /** Restore a prior OAuth sign-in, minting a fresh short-lived access token first. */
  fun restore() {
    val signedIn = prefs.getString(BASE_URL, null) ?: return
    val generation = ++connectionGeneration
    scope.launch {
      val refreshToken = vault.get(signedIn) ?: return@launch
      connect(signedIn, accessToken = null, refreshToken = refreshToken, generation = generation)
    }
  }

  fun authorizationIntent(url: String): Intent? {
    val normalized = url.trim().removeSuffix("/")
    pendingSignInUrl = normalized
    return try {
      require(normalized.startsWith("https://") || normalized.startsWith("http://")) {
        "Enter a full Home Assistant URL, including https://"
      }
      val intent = auth.prepareAuthIntent(normalized)
      // A restored session may still be refreshing in the background. Once the user starts a new
      // sign-in it must not be allowed to publish its old instance, snapshot or refresh token.
      pendingSignInGeneration = ++connectionGeneration
      connection = QuestConnection.SigningIn
      intent
    } catch (e: Exception) {
      // An invalid URL, or no browser to sign in with (BrowserSignInUnavailableException).
      pendingSignInUrl = null
      pendingSignInGeneration = null
      connection = QuestConnection.Error(e.message ?: "Enter a Home Assistant URL")
      null
    }
  }

  fun authorizationResult(data: Intent?) {
    scope.launch {
      try {
        data?.let(AuthorizationException::fromIntent)?.let { throw it }
        val response =
          data?.let(AuthorizationResponse::fromIntent)
            ?: error("Home Assistant sign-in returned no authorization response")
        val tokens = auth.exchangeCode(response)
        val url = pendingSignInUrl ?: error("No Home Assistant URL was pending")
        val generation = pendingSignInGeneration ?: error("Home Assistant sign-in was cancelled")
        connect(url, tokens.accessToken, tokens.refreshToken, generation)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        connection = QuestConnection.Error(e.message ?: "Home Assistant sign-in failed")
      } finally {
        pendingSignInUrl = null
        pendingSignInGeneration = null
      }
    }
  }

  fun signOut() {
    ++connectionGeneration
    pendingSignInUrl = null
    pendingSignInGeneration = null
    val closing = client
    val signedOut = baseUrl ?: prefs.getString(BASE_URL, null)
    client = null
    baseUrl = null
    snapshot = null
    importance = DashboardImportance.Empty
    pins = emptyList()
    connection = QuestConnection.SignedOut
    scope.launch {
      closing?.close()
      prefs.edit().remove(BASE_URL).apply()
      signedOut?.let { vault.clear(it) }
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

  fun updateControlsLocked(locked: Boolean) {
    controlsLocked = locked
    prefs.edit().putBoolean(CONTROLS_LOCKED, locked).apply()
  }

  /**
   * The server remains authoritative; the state_changed subscription redraws after its response.
   */
  fun toggle(entityId: String) {
    if (controlsLocked) return
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
    savePins(replaceQuestPin(pins, RoomPin(anchorId, url, entityId)))
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
    if (!ownsConnection(generation)) return
    connection = QuestConnection.Connecting
    val normalized = rawBaseUrl.trim().removeSuffix("/")
    var opened: HaClient? = null
    try {
      val refreshed =
        if (accessToken == null) refreshToken?.let { auth.refreshAccessToken(normalized, it) }
        else null
      val token =
        accessToken ?: refreshed?.accessToken ?: error("Home Assistant returned no access token")
      if (!ownsConnection(generation)) return
      (refreshed?.refreshToken ?: refreshToken)?.let {
        vault.put(normalized, it)
        prefs.edit().putString(BASE_URL, normalized).apply()
      }
      val connected = HaClient(HaConfig(normalized, token))
      opened = connected
      connected.connect()
      if (!ownsConnection(generation)) {
        connected.close()
        return
      }
      client?.close()
      client = connected
      baseUrl = normalized
      pins = decodeRoomPins(prefs.getString(PINS, null)).filter { it.baseUrl == normalized }
      val resolvedImportance = runCatching {
        dashboardImportance(connected.fetchDashboard())
      }
        .getOrDefault(DashboardImportance.Empty)
      if (!ownsConnection(generation)) return
      importance = resolvedImportance
      stateSnapshots(connected.subscribeEvents("state_changed")) {
          connected
            .snapshotWithDevices()
            .copy(areas = runCatching { connected.fetchAreas() }.getOrDefault(emptyMap()))
        }
        // End the old subscription when a sign-in or sign-out replaces it. Without this guard an
        // earlier restore could keep writing its live state over the newer Home Assistant session.
        .takeWhile { ownsConnection(generation) }
        .collect {
          if (!ownsConnection(generation)) return@collect
          snapshot = it
          connection = QuestConnection.Connected
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
    const val CONTROLS_LOCKED = "room_controls_locked"
    const val PINS = "room_pins"
    const val BASE_URL = "room_base_url"
  }

  private fun ownsConnection(generation: Int): Boolean = generation == connectionGeneration
}

sealed interface QuestConnection {
  data object SignedOut : QuestConnection

  data object SigningIn : QuestConnection

  data object Connecting : QuestConnection

  data object Connected : QuestConnection

  data class Error(val message: String) : QuestConnection
}

/** Replaces an entity's previous placement: a control has one spatial panel, not a growing set. */
internal fun replaceQuestPin(pins: List<RoomPin>, replacement: RoomPin): List<RoomPin> =
  pins.filter { it.entityId != replacement.entityId } + replacement
