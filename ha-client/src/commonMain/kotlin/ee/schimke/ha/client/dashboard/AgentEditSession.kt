package ee.schimke.ha.client.dashboard

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * An edit session held by the Terrazzo integration (`custom_components/terrazzo/edit_sessions.py`):
 * the handoff between a person and their agent changing one dashboard
 * (docs/architecture/dashboard-editing.md). The agent submits drafts over the integration's MCP
 * server; a person previews them and an administrator applies one. [baseline] and each draft's
 * config are present when the session was read through [AgentEdits], which always asks for them.
 */
@Serializable
data class AgentEditSession(
  @SerialName("session_id") val id: String,
  @SerialName("url_path") val urlPath: String? = null,
  val request: String = "",
  val status: String = OPEN,
  val notes: List<Note> = emptyList(),
  val drafts: List<Draft> = emptyList(),
  val applied: Applied? = null,
  val baseline: JsonObject? = null,
  @SerialName("expires_at") val expiresAt: Double = 0.0,
) {
  val isOpen: Boolean
    get() = status == OPEN

  val isApplied: Boolean
    get() = status == APPLIED

  /** The draft a person would review next: the newest. */
  val latestDraft: Draft?
    get() = drafts.lastOrNull()

  @Serializable
  data class Draft(
    val id: String,
    val summary: String = "",
    val at: Double = 0.0,
    val config: JsonObject? = null,
  )

  @Serializable data class Note(val text: String, val at: Double = 0.0)

  @Serializable
  data class Applied(@SerialName("draft_id") val draftId: String, val at: Double = 0.0)

  companion object {
    const val OPEN = "open"
    const val APPLIED = "applied"
    const val DISCARDED = "discarded"
  }
}

/**
 * A session just started, and the prompt to hand an agent: the MCP endpoint and session id only.
 */
data class StartedAgentEdit(val session: AgentEditSession, val prompt: String)

/**
 * The Terrazzo integration's edit sessions, as the signed-in user of one Home Assistant connection.
 * Every call is Home Assistant's to refuse: anyone who can see a dashboard may start a session,
 * read it and leave notes; only an administrator may [apply] or [undo]. [apply] and [undo] throw
 * [DashboardConflictException] when the dashboard changed meanwhile, and write nothing.
 */
interface AgentEdits {
  suspend fun start(urlPath: String?, request: String): StartedAgentEdit

  /** The open sessions on [urlPath], newest first. */
  suspend fun open(urlPath: String?): List<AgentEditSession>

  suspend fun get(sessionId: String): AgentEditSession

  /** [get], with the prompt to hand an agent again: picking a session back up. */
  suspend fun resume(sessionId: String): StartedAgentEdit

  /** The session now and after every change, while collected. */
  fun watch(sessionId: String): Flow<AgentEditSession>

  /** "Request changes": a note the agent reads on its next call. */
  suspend fun note(sessionId: String, text: String): AgentEditSession

  suspend fun discard(sessionId: String): AgentEditSession

  suspend fun apply(sessionId: String, draftId: String): AgentEditSession

  suspend fun undo(sessionId: String): AgentEditSession
}
