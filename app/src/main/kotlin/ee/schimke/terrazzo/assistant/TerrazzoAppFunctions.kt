package ee.schimke.terrazzo.assistant

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionDeniedException
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionPermissionRequiredException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import ee.schimke.ha.model.EntityState
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.SessionConnectionStatus
import ee.schimke.terrazzo.terrazzoGraph
import ee.schimke.terrazzo.widget.WidgetActionRunner
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home Assistant as AppFunctions: what an on-device agent holding `EXECUTE_APP_FUNCTIONS` (Gemini,
 * once Google lets it see this app) can read and switch through Terrazzo.
 *
 * Every function ships disabled. The Settings switch ([AssistantAccess]) enables them all for the
 * signed-in instance, so nothing reaches Home Assistant until its owner turns it on.
 *
 * The rules match the launcher widgets ([WidgetActionRunner]): only the instance the app is signed
 * in to, only with the app's own login (its live socket while it runs, else a token minted from the
 * stored refresh token), and only on/off-style service calls. Locks and alarm panels can be read
 * but never switched ([AssistantEntities.writeRefusal]). In demo mode the functions read the demo
 * house and their writes do nothing, which lets a tester try an agent without a Home Assistant.
 *
 * The AppFunctions compiler generates `TerrazzoAppFunctionService` (declared in the manifest) and
 * the `terrazzo_app_functions.xml` asset describing these functions from their KDoc.
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
@AppFunctionServiceEntryPoint(
  serviceName = "TerrazzoAppFunctionService",
  appFunctionXmlFileName = "terrazzo_app_functions",
)
abstract class TerrazzoAppFunctions : AppFunctionService() {

  private val graph: TerrazzoGraph
    get() = applicationContext.terrazzoGraph()

  /**
   * Finds Home Assistant entities (lights, switches, sensors, scenes, scripts and others) by name.
   * Call this first to turn what the user said into an entity id for the other functions.
   *
   * @param query Words to match against the entity's name or id, such as "kitchen light". Leave
   *   blank to list entities.
   * @param domain Only entities of this Home Assistant domain, such as "light", "switch", "sensor",
   *   "scene" or "script". Leave blank for every domain.
   * @return Up to 25 matching entities with their current state, best match first.
   */
  @AppFunctionDeclaration(isDescribedByKDoc = true, isEnabled = false)
  suspend fun findEntities(query: String? = null, domain: String? = null): HomeEntities =
    withHome { session ->
      HomeEntities(
        AssistantEntities.search(states(session).values, query, domain).map { it.toHomeEntity() }
      )
    }

  /**
   * Reads one Home Assistant entity's current state and unit.
   *
   * @param entityId The entity id, such as "sensor.living_room_temperature", from findEntities.
   * @return The entity and its current state.
   */
  @AppFunctionDeclaration(isDescribedByKDoc = true, isEnabled = false)
  suspend fun getEntityState(entityId: String): HomeEntity = withHome { session ->
    entity(session, entityId).toHomeEntity()
  }

  /**
   * Turns a Home Assistant entity on: a light, switch, fan or similar, or runs a scene or script.
   *
   * @param entityId The entity id from findEntities, such as "light.kitchen" or "scene.movie".
   * @return The entity as it was before the call. Home Assistant reports the new state shortly
   *   after.
   */
  @AppFunctionDeclaration(isDescribedByKDoc = true, isEnabled = false)
  suspend fun turnOn(entityId: String): HomeEntity = switch(entityId, "turn_on")

  /**
   * Turns a Home Assistant entity off: a light, switch, fan or similar.
   *
   * @param entityId The entity id from findEntities, such as "light.kitchen".
   * @return The entity as it was before the call. Home Assistant reports the new state shortly
   *   after.
   */
  @AppFunctionDeclaration(isDescribedByKDoc = true, isEnabled = false)
  suspend fun turnOff(entityId: String): HomeEntity = switch(entityId, "turn_off")

  /**
   * Toggles a Home Assistant entity between on and off.
   *
   * @param entityId The entity id from findEntities, such as "switch.coffee_machine".
   * @return The entity as it was before the call. Home Assistant reports the new state shortly
   *   after.
   */
  @AppFunctionDeclaration(isDescribedByKDoc = true, isEnabled = false)
  suspend fun toggle(entityId: String): HomeEntity = switch(entityId, "toggle")

  private suspend fun switch(entityId: String, service: String): HomeEntity = withHome { session ->
    AssistantEntities.writeRefusal(entityId)?.let { throw AppFunctionDeniedException(it) }
    val before = entity(session, entityId)
    // `homeassistant.*` dispatches to the entity's own domain, so one call covers lights,
    // switches, fans, scenes and scripts alike.
    session.callService("homeassistant", service, entityId = entityId)
    graph.logStore.recordLocalAction("Assistant $service", entityId)
    before.toHomeEntity()
  }

  private suspend fun states(session: HaSession): Map<String, EntityState> =
    session.liveStates.value?.states?.takeIf { it.isNotEmpty() } ?: session.loadSnapshot().states

  private suspend fun entity(session: HaSession, entityId: String): EntityState {
    if (AssistantEntities.domain(entityId).isEmpty()) {
      throw AppFunctionInvalidArgumentException("$entityId isn't an entity id like light.kitchen")
    }
    return states(session)[entityId]
      ?: throw AppFunctionElementNotFoundException("No entity $entityId; use findEntities")
  }

  /**
   * Runs [block] on a session for the instance the app is signed in to: the app's connected socket
   * when there is one, else a short-lived session on the app's login, closed afterwards.
   */
  private suspend fun <T> withHome(block: suspend (HaSession) -> T): T =
    try {
      withTimeout(CALL_TIMEOUT_MS) { onHome(block) }
    } catch (timeout: TimeoutCancellationException) {
      throw AppFunctionAppUnknownException("Home Assistant didn't answer in time")
    }

  private suspend fun <T> onHome(block: suspend (HaSession) -> T): T {
    if (graph.preferencesStore.demoModeNow()) return block(graph.sessionFactory.createDemo())
    val baseUrl =
      graph.offlineCache.lastInstance()?.takeIf { graph.tokenVault.get(it) != null }
        ?: throw AppFunctionPermissionRequiredException(
          "Sign in to Home Assistant in the Terrazzo app first"
        )
    WidgetActionRunner.connectedAppSession(baseUrl)?.let {
      return block(it)
    }
    val session =
      graph.sessionFactory.create(baseUrl, WidgetActionRunner.accessToken(graph, baseUrl))
    try {
      session.connect()
      // The cache wrapper swallows a failed connect and would answer from disk; an agent must
      // never be told old states are current, so an unreachable Home Assistant is an error.
      if (session.connectionStatus.value != SessionConnectionStatus.Connected) {
        throw AppFunctionAppUnknownException("Can't reach Home Assistant at $baseUrl")
      }
      return block(session)
    } catch (failure: Throwable) {
      // A refused token is dropped so the next call mints one; an agent's own mistake isn't that.
      if (failure !is AppFunctionException) WidgetActionRunner.forgetAccessToken(baseUrl)
      throw failure
    } finally {
      // A timed-out call is cancelled; the socket must still close, so close outside that.
      withContext(NonCancellable) {
        withTimeoutOrNull(CLOSE_TIMEOUT_MS) { runCatching { session.close() } }
      }
    }
  }

  private companion object {
    const val CALL_TIMEOUT_MS = 15_000L
    const val CLOSE_TIMEOUT_MS = 2_000L
  }
}

/** A Home Assistant entity as an agent sees it. */
@AppFunctionSerializable(isDescribedByKDoc = true)
class HomeEntity(
  /** The entity id, such as "light.kitchen"; pass it to the other functions. */
  val entityId: String,
  /** The name Home Assistant shows for the entity. */
  val name: String,
  /** The entity's domain, such as "light" or "sensor". */
  val domain: String,
  /** The current state, such as "on", "off", "unavailable" or a sensor reading like "21.5". */
  val state: String,
  /** The reading's unit, such as "°C" or "%", when it has one. */
  val unit: String?,
  /** Whether Terrazzo lets an assistant switch it; locks and alarm panels are read-only. */
  val canSwitch: Boolean,
)

/** Entities matching a search. */
@AppFunctionSerializable(isDescribedByKDoc = true)
class HomeEntities(
  /** The matches, best first. */
  val entities: List<HomeEntity>
)

private fun EntityState.toHomeEntity(): HomeEntity =
  HomeEntity(
    entityId = entityId,
    name = AssistantEntities.name(this),
    domain = AssistantEntities.domain(entityId),
    state = state,
    unit = AssistantEntities.unit(this),
    canSwitch = AssistantEntities.writeRefusal(entityId) == null,
  )
