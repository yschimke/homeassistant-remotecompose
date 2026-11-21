package ee.schimke.ha.designserver

import ee.schimke.livery.sessions.Codes
import ee.schimke.livery.sessions.DraftStore
import ee.schimke.livery.sessions.Message
import ee.schimke.livery.sessions.SecureEntropy
import ee.schimke.livery.sessions.SessionLinks
import ee.schimke.livery.sessions.SessionRelay
import kotlinx.coroutines.flow.StateFlow

/**
 * The live side of a card or dashboard agent session, on Livery's session relay
 * (`livery-sessions-core`, docs/SESSIONS.md there): how an agent joins without a pasted capability,
 * who is connected, the conversation between the person and the agent, and the person's decision.
 * Both sides connect out to this server and long-poll it, so it works from the phone, the desktop
 * app and the browser (Wasm, Wasmo) behind any reverse proxy, and Home Assistant is never reached
 * from outside.
 *
 * This keeps the design server's own names and wire shapes; the rules (codes and their shared
 * failed-code budget, links, presence, long-polls, what a restart keeps) are Livery's. The drafts
 * stay in [AgentDesignSessions] and [DashboardAgentSessions], which keep validating them, and are
 * the relay's `card` and `dashboard` [DraftStore]s. Nothing here holds a Home Assistant credential,
 * and the agent can never apply a draft.
 */
class AgentRelay(
  val cards: AgentDesignSessions,
  val dashboards: DashboardAgentSessions,
  clock: () -> Long = System::currentTimeMillis,
  /** The longest a long-poll is held: under common reverse-proxy idle timeouts. */
  val maxWaitMs: Long = SessionRelay.MAX_WAIT_MS,
) {
  enum class Kind(val wire: String) {
    Card("card"),
    Dashboard("dashboard");

    companion object {
      fun of(wire: String): Kind = entries.first { it.wire == wire }
    }
  }

  /** What the person's app shows: everything but the draft itself, which the stores serve. */
  data class OwnerState(
    val version: Long,
    val kind: Kind,
    val title: String,
    val revision: Long,
    val expiresInSeconds: Long,
    val joinCode: String?,
    val joinCodeExpiresInSeconds: Long,
    val agent: SessionRelay.Presence,
    val messages: List<Message>,
    val decision: String?,
    /** Sent to the person's linked agent: no code to hand over. */
    val linked: Boolean = false,
  )

  data class AgentView(
    val kind: Kind,
    val title: String,
    val messages: List<Message>,
    val cursor: Long,
    val decision: String?,
    val personWatching: Boolean,
    val expiresInSeconds: Long,
  )

  data class Joined(val session: String, val kind: Kind, val title: String)

  data class Pending(val code: String, val poll: String, val expiresInSeconds: Long)

  /** What the person let the agent into: one session, or (from setup) a lasting link. */
  data class PollResult(val joined: Joined?, val link: String? = null)

  enum class ClaimResult {
    Claimed,
    Unknown,
    Throttled,
    Gone,
  }

  private val relay =
    SessionRelay(
      stores =
        mapOf(
          Kind.Card.wire to
            object : DraftStore {
              override fun revision(owner: String) = cards.read(owner)?.revision

              override fun touch(owner: String) = cards.touch(owner)

              override fun revoke(owner: String) = cards.revoke(owner)
            },
          Kind.Dashboard.wire to
            object : DraftStore {
              override fun revision(owner: String) = dashboards.read(owner)?.revision

              override fun touch(owner: String) = dashboards.touch(owner)

              override fun revoke(owner: String) = dashboards.revoke(owner)

              // Agents that still read the dashboard request directly see the comment as a note.
              override fun note(owner: String, text: String) {
                dashboards.note(owner, text)
              }
            },
        ),
      entropy = SecureEntropy(),
      clock = clock,
      maxWaitMs = maxWaitMs,
    )

  init {
    // Store callbacks run under the store's lock: SessionRelay.changed takes none.
    cards.onChange = relay::changed
    dashboards.onChange = relay::changed
  }

  /** People's lasting connections to their agents: requests go to them without a code. */
  val links: SessionLinks
    get() = relay.links

  /** Bumped on every change to any session or link: what [AgentSessionFile] saves after. */
  val changes: StateFlow<Long>
    get() = relay.changes

  suspend fun snapshot(): SessionRelay.Snapshot = relay.snapshot()

  /** Restore after the stores have restored theirs: only sessions they still hold come back. */
  suspend fun restore(saved: SessionRelay.Snapshot) = relay.restore(saved)

  /**
   * Start relaying a session the stores just created. With the person's connected [link] it goes
   * straight to their agent and needs no code (null); otherwise it returns the code to hand over.
   */
  suspend fun register(
    kind: Kind,
    owner: String,
    agent: String,
    title: String,
    link: String? = null,
  ): String? = relay.register(kind.wire, owner, agent, title, link)

  // ---- The person's side (owner capability) ----

  fun isOwner(token: String) = relay.isOwner(token)

  /** The state now, or once it changes past [since] (up to [maxWaitMs]); null when gone. */
  suspend fun ownerState(
    owner: String,
    since: Long? = null,
    waitMs: Long = maxWaitMs,
  ): OwnerState? =
    relay.ownerState(owner, since, waitMs)?.let {
      OwnerState(
        it.version,
        Kind.of(it.kind),
        it.title,
        it.revision,
        it.expiresInSeconds,
        it.joinCode,
        it.joinCodeExpiresInSeconds,
        it.agent,
        it.messages,
        it.decision,
        it.linked,
      )
    }

  /** A fresh join code, replacing any unused one. */
  suspend fun newJoinCode(owner: String): String? = relay.newJoinCode(owner)

  /** Let the agent that showed the person [code] (from `request_access`) into this session. */
  suspend fun claim(owner: String, code: String): ClaimResult = relay.claim(owner, code).ours()

  /** Let the agent that showed the person [code] (from `request_access`) into their [link]. */
  suspend fun claimLink(link: String, code: String): ClaimResult =
    relay.claimLink(link, code).ours()

  /** The person's app shares the draft as it drew it, so the agent sees exactly that. */
  suspend fun putRender(owner: String, png: ByteArray, revision: Long): Boolean =
    relay.putRender(owner, png, revision)

  /** The latest render for the agent: PNG bytes and the draft revision they show. */
  suspend fun render(agent: String): Pair<ByteArray, Long>? = relay.render(agent)

  suspend fun comment(owner: String, text: String, target: String?): Boolean =
    relay.comment(owner, text, target)

  suspend fun decide(owner: String, accepted: Boolean, text: String?): Boolean =
    relay.decide(owner, accepted, text)

  /** Revoke from the owner side: the store forgets the session and both sides see it end. */
  suspend fun revoke(owner: String): Boolean = relay.revoke(owner)

  // ---- The agent's side (agent capability) ----

  fun kindOf(agent: String): Kind? = relay.kindOf(agent)?.let(Kind::of)

  /** Redeem the code the person's app shows. Single use; returns the agent capability. */
  suspend fun join(code: String, agentName: String?): Joined? = relay.join(code, agentName)?.ours()

  /**
   * Redeem the code the person gave their agent in setup: the agent's link capability, or null when
   * [code] isn't an unused link code.
   */
  suspend fun connect(code: String, agentName: String?): String? = relay.connect(code, agentName)

  /** An agent asks first: it shows the person [Pending.code] and polls with [Pending.poll]. */
  suspend fun requestAccess(agentName: String?): Pending =
    relay.requestAccess(agentName).let { Pending(it.code, it.poll, it.expiresInSeconds) }

  /** Waits for the person to enter the code; null once the request is unknown or expired. */
  suspend fun pollAccess(poll: String, waitMs: Long = maxWaitMs): PollResult? =
    relay.pollAccess(poll, waitMs)?.let { PollResult(it.joined?.ours(), it.link) }

  /** Record that the agent is active, optionally with what it's doing. Null when gone. */
  suspend fun seen(agent: String, status: String? = null): Kind? =
    relay.seen(agent, status)?.let(Kind::of)

  suspend fun agentMessage(agent: String, text: String, kind: String = Message.MESSAGE): Boolean =
    relay.agentMessage(agent, text, kind)

  /**
   * The person's comments and decisions after [since], waiting up to [waitMs] for one when there
   * are none yet. Null when the session is gone.
   */
  suspend fun awaitFeedback(agent: String, since: Long, waitMs: Long = maxWaitMs): AgentView? =
    relay.awaitFeedback(agent, since, waitMs)?.ours()

  suspend fun agentView(agent: String, since: Long = 0): AgentView? =
    relay.agentView(agent, since)?.ours()

  /** Drop sessions the stores have expired that nobody came back to. */
  internal suspend fun prune() = relay.prune()

  /** Sessions the relay still indexes (for tests). */
  internal val size: Int
    get() = relay.size

  /** Access requests still indexed (for tests). */
  internal suspend fun pendingRequests(): Int = relay.pendingRequests()

  private fun SessionRelay.ClaimResult.ours() = ClaimResult.valueOf(name)

  private fun SessionRelay.Joined.ours() = Joined(session, Kind.of(kind), title)

  private fun SessionRelay.AgentView.ours() =
    AgentView(Kind.of(kind), title, messages, cursor, decision, personWatching, expiresInSeconds)

  companion object {
    const val JOIN_CODE_MS = SessionRelay.CODE_MS
    const val REQUEST_MS = SessionRelay.REQUEST_MS
    const val CONNECTED_MS = SessionRelay.CONNECTED_MS
    const val WATCHING_MS = SessionRelay.WATCHING_MS
    const val MAX_TEXT = SessionRelay.MAX_TEXT
    const val MAX_RENDER_BYTES = SessionRelay.MAX_RENDER_BYTES
    const val MAX_FAILURES_PER_MINUTE = SessionRelay.MAX_FAILURES_PER_MINUTE

    fun formatCode(code: String) = Codes.format(code)

    /** Upper-cased, separators dropped; null unless it is eight code characters. */
    fun normalizeCode(code: String): String? = Codes.normalize(code)

    internal fun cleanName(name: String?) = Codes.cleanName(name)
  }
}
