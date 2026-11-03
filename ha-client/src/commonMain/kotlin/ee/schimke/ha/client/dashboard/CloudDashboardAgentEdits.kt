package ee.schimke.ha.client.dashboard

import ee.schimke.ha.client.HaClient
import ee.schimke.ha.client.agent.AgentGrantStore
import ee.schimke.ha.client.agent.AgentLiveControls
import ee.schimke.ha.client.agent.AgentRelayClient
import ee.schimke.ha.client.agent.AgentRelayState
import ee.schimke.ha.client.agent.AgentSessionEndedException
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A Home Assistant edit session whose agent works through the design server's relay
 * (docs/architecture/agent-sessions.md): the person gives the agent a short code, the screen
 * long-polls the relay, and each draft is handed to Home Assistant as the signed-in person, which
 * checks it and applies it only when they say so. No Home Assistant token leaves this device.
 */
class CloudDashboardAgentEdits(
  private val home: HaClient,
  server: String,
  engine: HttpClientEngine? = null,
  private val grants: AgentGrantStore = AgentGrantStore.memory(),
  /** The person's agent link on this design server, when set up: requests go straight to it. */
  private val agentLink: () -> String? = { null },
) : AgentEdits, AgentLiveControls {
  private val relay = AgentRelayClient(server, engine)
  override val relayServer: String = relay.base
  private val delegate = home.agentEdits

  /** One session's owner capability and the newest draft already handed to Home Assistant. */
  @Serializable
  private data class Access(val owner: String, var revision: Long = 0, val server: String = "")

  private val json = Json { ignoreUnknownKeys = true }

  private val sessions: MutableMap<String, Access> =
    grants
      .load()
      ?.let { runCatching { json.decodeFromString<Map<String, Access>>(it) }.getOrNull() }
      .orEmpty()
      // A grant for another design server can't be used with this one.
      .filterValues { it.server == relay.base }
      .toMutableMap()

  private val states = mutableMapOf<String, MutableStateFlow<AgentRelayState?>>()

  private fun persist() {
    grants.save(if (sessions.isEmpty()) null else json.encodeToString(sessions.toMap()))
  }

  private fun access(sessionId: String) = sessions[sessionId] ?: throw AgentSessionEndedException()

  private fun liveState(sessionId: String) = states.getOrPut(sessionId) { MutableStateFlow(null) }

  override fun live(sessionId: String): StateFlow<AgentRelayState?> = liveState(sessionId)

  override suspend fun start(urlPath: String?, request: String): StartedAgentEdit {
    val started = delegate.start(urlPath, request)
    try {
      val owner =
        relay.startDashboard(request, requireNotNull(started.session.baseline), agentLink())
      sessions[started.session.id] = Access(owner, server = relay.base)
      persist()
      // The code shows at once, before the screen starts watching.
      refresh(started.session.id)
      return StartedAgentEdit(started.session, "")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Don't leave an orphan Home Assistant request when the design server refused.
      runCatching { delegate.discard(started.session.id) }
      throw e
    }
  }

  override suspend fun open(urlPath: String?) = delegate.open(urlPath).filter { it.id in sessions }

  override suspend fun get(sessionId: String) = delegate.get(sessionId)

  override suspend fun resume(sessionId: String) = StartedAgentEdit(delegate.get(sessionId), "")

  /**
   * The Home Assistant session after each relay answer: held by the design server until something
   * changes (25 s at most), so there is no fixed polling. A new draft is handed to Home Assistant
   * on the way.
   */
  override fun watch(sessionId: String) = flow {
    val access = access(sessionId)
    var since: Long? = null
    while (true) {
      val state =
        try {
          relay.state(access.owner, since)
        } catch (e: AgentSessionEndedException) {
          sessions.remove(sessionId)
          persist()
          throw e
        }
      liveState(sessionId).value = state
      since = state.version
      if (state.revision > access.revision) handOver(sessionId, access)
      emit(delegate.get(sessionId))
    }
  }

  /** Give Home Assistant the agent's newest draft, which it checks as this person. */
  private suspend fun handOver(sessionId: String, access: Access) {
    val draft = relay.dashboardDraft(access.owner)
    if (draft.revision <= access.revision) return
    home.relayAgentDraft(sessionId, draft.dashboard, draft.summary, "cloud:${draft.revision}")
    access.revision = draft.revision
    persist()
  }

  override suspend fun note(sessionId: String, text: String, target: String?): AgentEditSession {
    comment(sessionId, text, target)
    return delegate.note(sessionId, text, target)
  }

  override suspend fun comment(sessionId: String, text: String, target: String?) =
    relay.comment(access(sessionId).owner, text, target)

  override suspend fun newJoinCode(sessionId: String) {
    relay.newJoinCode(access(sessionId).owner)
    refresh(sessionId)
  }

  override suspend fun claim(sessionId: String, code: String) {
    relay.claim(access(sessionId).owner, code)
    refresh(sessionId)
  }

  private suspend fun refresh(sessionId: String) {
    val state = runCatching { relay.state(access(sessionId).owner) }.getOrNull() ?: return
    liveState(sessionId).value = state
  }

  override suspend fun discard(sessionId: String): AgentEditSession {
    sessions[sessionId]?.let { relay.revoke(it.owner) }
    val result = delegate.discard(sessionId)
    sessions.remove(sessionId)
    persist()
    states.remove(sessionId)
    return result
  }

  override suspend fun apply(sessionId: String, draftId: String): AgentEditSession {
    val applied = delegate.apply(sessionId, draftId)
    // Tell a waiting agent it's done; the apply itself already happened in Home Assistant.
    sessions[sessionId]?.let { runCatching { relay.decide(it.owner, accepted = true) } }
    return applied
  }

  override suspend fun undo(sessionId: String) = delegate.undo(sessionId)

  /** The relay keeps the agent's view; a relayed draft's id is `cloud:<revision>`. */
  override suspend fun shareRender(sessionId: String, draftId: String, png: ByteArray) {
    val access = access(sessionId)
    val revision = draftId.removePrefix("cloud:").toLongOrNull() ?: access.revision
    relay.putRender(access.owner, png, revision)
  }

  fun close() = relay.close()
}
