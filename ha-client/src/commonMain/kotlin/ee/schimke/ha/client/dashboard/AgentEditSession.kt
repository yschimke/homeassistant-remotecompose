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
  /** What the agent said to the person, in the same sequence as [notes]. */
  val messages: List<Message> = emptyList(),
  /** The agent's last activity, when it has joined: for the live status line. */
  val agent: Activity? = null,
  /** Home Assistant's clock when this was read ([Activity.seenAt] is in it); 0 when unknown. */
  @SerialName("server_time") val serverTime: Double = 0.0,
  /**
   * An agent signed in to Home Assistant is listening for requests (`await_request`): it picks this
   * session up by itself, so there's no prompt to hand over.
   */
  @SerialName("agent_listening") val agentListening: Boolean = false,
  /**
   * The design server request a cloud agent works on, linked by whoever started the session: only
   * their own clients read it, and with it any of them picks the request back up.
   */
  val cloud: CloudLink? = null,
  /** What the session is about: the whole dashboard (null) or one section of it. */
  val scope: EditScope? = null,
) {
  /** Seconds since the agent was last active, by Home Assistant's clock; null before it joined. */
  val agentIdleSeconds: Double?
    get() = agent?.let { if (serverTime > 0) (serverTime - it.seenAt).coerceAtLeast(0.0) else null }

  /** The person's notes and the agent's messages, in the order they were said. */
  val conversation: List<Said>
    get() =
      (notes.map { Said(it.seq, fromAgent = false, it.text, it.target) } +
          messages.map { Said(it.seq, fromAgent = true, it.text, null) })
        .sortedBy { it.seq }

  data class Said(val seq: Long, val fromAgent: Boolean, val text: String, val target: String?)

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
    /** For a draft relayed from a cloud agent, `cloud:<relay revision>`. */
    @kotlinx.serialization.SerialName("relay_id") val relayId: String? = null,
  )

  @Serializable
  data class Note(
    val text: String,
    val at: Double = 0.0,
    /** The card the note is about, when the person pinned it to one. */
    val target: String? = null,
    val seq: Long = 0,
  )

  @Serializable data class Message(val text: String, val at: Double = 0.0, val seq: Long = 0)

  @Serializable
  data class Activity(
    val status: String = "",
    @SerialName("seen_at") val seenAt: Double = 0.0,
  )

  /** A design server ([server]) and the owner capability of a request on it. */
  @Serializable data class CloudLink(val server: String, val owner: String)

  @Serializable
  data class Applied(@SerialName("draft_id") val draftId: String, val at: Double = 0.0)

  companion object {
    const val OPEN = "open"
    const val APPLIED = "applied"
    const val DISCARDED = "discarded"
  }
}

/**
 * What an edit session is about: the whole dashboard, or one of its sections ([view] and [section]
 * index its config's `views[].sections[]`). Home Assistant refuses a section session's drafts that
 * change anything else. [label] is how the person and the agent name it ("Garage").
 */
@Serializable
data class EditScope(
  val kind: String = DASHBOARD,
  val view: Int? = null,
  val section: Int? = null,
  val label: String? = null,
) {
  val isSection: Boolean
    get() = kind == SECTION && view != null && section != null

  companion object {
    const val DASHBOARD = "dashboard"
    const val SECTION = "section"

    fun section(view: Int, section: Int, label: String?) = EditScope(SECTION, view, section, label)
  }
}

/** How a session is named in a list or a title: its section's label, or the dashboard's. */
fun AgentEditSession.scopeLabel(dashboardTitle: String?): String =
  scope?.takeIf { it.isSection }?.let { it.label ?: "Section ${(it.section ?: 0) + 1}" }
    ?: dashboardTitle
    ?: "Dashboard"

/**
 * What a session opened without typing asks of the agent: the person says the rest in the
 * conversation and on the preview.
 */
fun EditScope?.openingRequest(dashboardTitle: String?): String {
  val what =
    this?.takeIf { it.isSection }?.let { "the ${it.label ?: "section"} section of " }.orEmpty() +
      (dashboardTitle?.let { "the $it dashboard" } ?: "this dashboard")
  return "Work on $what with me. Start with any comments I've left on the preview; if there are " +
    "none yet, say hello and follow my comments as they come."
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
  /**
   * A session on [urlPath] about [scope] (the whole dashboard when null); [request] may be empty.
   */
  suspend fun start(urlPath: String?, request: String, scope: EditScope? = null): StartedAgentEdit

  /** The open sessions on [urlPath], newest first. */
  suspend fun open(urlPath: String?): List<AgentEditSession>

  /** The open sessions on [urlPath] and the recently applied or discarded ones, newest first. */
  suspend fun recent(urlPath: String?): List<AgentEditSession> = open(urlPath)

  /**
   * What to hand one or more agents so they join [sessionId]: the same text for each, so several
   * can work on it.
   */
  suspend fun prompt(sessionId: String): String = resume(sessionId).prompt

  suspend fun get(sessionId: String): AgentEditSession

  /** [get], with the prompt to hand an agent again: picking a session back up. */
  suspend fun resume(sessionId: String): StartedAgentEdit

  /** The session now and after every change, while collected. */
  fun watch(sessionId: String): Flow<AgentEditSession>

  /**
   * "Request changes": a note for the agent, optionally about one card ([target]). An agent waiting
   * in `await_feedback` gets it at once; others on their next call.
   */
  suspend fun note(sessionId: String, text: String, target: String? = null): AgentEditSession

  suspend fun discard(sessionId: String): AgentEditSession

  suspend fun apply(sessionId: String, draftId: String): AgentEditSession

  suspend fun undo(sessionId: String): AgentEditSession

  /**
   * Share [draftId] as this app drew it ([png]), so the agent sees exactly what the person sees.
   * Best effort: an older integration or server just doesn't keep it.
   */
  suspend fun shareRender(sessionId: String, draftId: String, png: ByteArray) {}

  /**
   * Keep a cloud agent's design server request ([server], [owner]) with the session, so this
   * person's other clients can pick it up and Home Assistant relays its drafts with no app open. An
   * integration too old for it refuses with `unknown_command`.
   */
  suspend fun linkCloud(sessionId: String, server: String, owner: String) {}
}
