package ee.schimke.ha.designserver

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The live side of a card or dashboard agent session: how an agent joins it without a pasted
 * capability, who is connected, the conversation between the person and the agent, and the person's
 * decision. Both sides connect out to this server and long-poll it, so it works from the phone, the
 * desktop app and the browser (Wasm, Wasmo) behind any reverse proxy, and Home Assistant is never
 * reached from outside.
 *
 * The drafts themselves stay in [AgentDesignSessions] and [DashboardAgentSessions], which keep
 * validating them; this only adds the relay around them. Nothing here holds a Home Assistant
 * credential, and the agent can never apply a draft.
 */
class AgentRelay(
  val cards: AgentDesignSessions,
  val dashboards: DashboardAgentSessions,
  private val clock: () -> Long = System::currentTimeMillis,
  /** The longest a long-poll is held: under common reverse-proxy idle timeouts. */
  val maxWaitMs: Long = 25_000,
) {
  enum class Kind(val wire: String) {
    Card("card"),
    Dashboard("dashboard"),
  }

  /** One line of the conversation: a person's comment or decision, or an agent's message. */
  data class Message(
    val seq: Long,
    val from: String,
    val kind: String,
    val text: String,
    val target: String?,
    val at: Long,
  )

  data class Presence(
    val joined: Boolean,
    val connected: Boolean,
    val name: String,
    val status: String,
    val lastSeenMs: Long?,
  )

  /** What the person's app shows: everything but the draft itself, which the stores serve. */
  data class OwnerState(
    val version: Long,
    val kind: Kind,
    val title: String,
    val revision: Long,
    val expiresInSeconds: Long,
    val joinCode: String?,
    val joinCodeExpiresInSeconds: Long,
    val agent: Presence,
    val messages: List<Message>,
    val decision: String?,
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

  private class Entry(val kind: Kind, val owner: String, val agent: String, val title: String) {
    val version = MutableStateFlow(1L)
    var joinCode: String? = null
    var joinCodeExpires = 0L
    var agentName = "Agent"
    var agentStatus = ""
    var agentSeen = 0L
    var personSeen = 0L
    var seq = 0L
    val messages = ArrayList<Message>()
    var decision: String? = null

    fun bump() = version.update { it + 1 }
  }

  private class Request(val code: String, val poll: String, val name: String, val expires: Long) {
    @Volatile var entry: Entry? = null
    val done = MutableStateFlow(false)
  }

  private val byOwner = ConcurrentHashMap<String, Entry>()
  private val byAgent = ConcurrentHashMap<String, Entry>()
  private val codes = HashMap<String, Entry>()
  private val requests = HashMap<String, Request>()
  private val pollers = HashMap<String, Request>()
  private val failures = ArrayDeque<Long>()

  init {
    // Store callbacks run under the store's lock: only flip a flow here, never take ours.
    cards.onChange = { owner -> byOwner[owner]?.bump() }
    dashboards.onChange = { owner -> byOwner[owner]?.bump() }
  }

  /** Start relaying a session the stores just created; returns its first join code. */
  fun register(kind: Kind, owner: String, agent: String, title: String): String {
    prune()
    val entry = Entry(kind, owner, agent, title.take(200))
    byOwner[owner] = entry
    byAgent[agent] = entry
    return synchronized(this) { issueCode(entry) }
  }

  // ---- The person's side (owner capability) ----

  fun isOwner(token: String) = token.isNotEmpty() && byOwner[token]?.let(::alive) == true

  /** The state now, or once it changes past [since] (up to [maxWaitMs]); null when gone. */
  suspend fun ownerState(
    owner: String,
    since: Long? = null,
    waitMs: Long = maxWaitMs,
  ): OwnerState? {
    var entry = byOwner[owner] ?: return null
    if (!alive(entry)) return forget(entry)
    if (since != null && entry.version.value <= since) {
      // Mark the person present before waiting, so a joining agent sees them watching.
      synchronized(this) { entry.personSeen = clock() }
      withTimeoutOrNull(waitMs.coerceIn(0, maxWaitMs)) { entry.version.first { it > since } }
      entry = byOwner[owner] ?: return null
      if (!alive(entry)) return forget(entry)
    }
    val left = touch(entry) ?: return forget(entry)
    val revision =
      when (entry.kind) {
        Kind.Card -> cards.read(owner)?.revision
        Kind.Dashboard -> dashboards.read(owner)?.revision
      } ?: return forget(entry)
    return synchronized(this) {
      val now = clock()
      entry.personSeen = now
      if (entry.joinCodeExpires <= now) dropCode(entry)
      OwnerState(
        version = entry.version.value,
        kind = entry.kind,
        title = entry.title,
        revision = revision,
        expiresInSeconds = left,
        joinCode = entry.joinCode,
        joinCodeExpiresInSeconds = ((entry.joinCodeExpires - now) / 1000).coerceAtLeast(0),
        agent = presence(entry, now),
        messages = entry.messages.takeLast(OWNER_MESSAGES),
        decision = entry.decision,
      )
    }
  }

  /** A fresh join code, replacing any unused one. */
  fun newJoinCode(owner: String): String? {
    val entry = byOwner[owner]?.takeIf(::alive) ?: return null
    touch(entry)
    return synchronized(this) { issueCode(entry) }.also { entry.bump() }
  }

  /** Let the agent that showed the person [code] (from `request_access`) into this session. */
  fun claim(owner: String, code: String): ClaimResult {
    val entry = byOwner[owner]?.takeIf(::alive) ?: return ClaimResult.Gone
    val normalized = normalizeCode(code) ?: return ClaimResult.Unknown
    val request =
      synchronized(this) {
        expireRequests()
        if (throttled()) return ClaimResult.Throttled
        val found = requests.remove(normalized)
        if (found == null) {
          failures.addLast(clock())
          return ClaimResult.Unknown
        }
        found.entry = entry
        entry.agentName = found.name
        entry.agentSeen = clock()
        entry.agentStatus = "Connected"
        found
      }
    request.done.value = true
    entry.bump()
    return ClaimResult.Claimed
  }

  enum class ClaimResult {
    Claimed,
    Unknown,
    Throttled,
    Gone,
  }

  fun comment(owner: String, text: String, target: String?): Boolean {
    val entry = byOwner[owner]?.takeIf(::alive) ?: return false
    require(text.isNotBlank() && text.length <= MAX_TEXT && (target?.length ?: 0) <= 200)
    // Agents that still read the dashboard request directly see the comment as a note.
    if (entry.kind == Kind.Dashboard) dashboards.note(owner, text)
    append(entry, "person", "comment", text.trim(), target?.trim()?.ifEmpty { null })
    return true
  }

  fun decide(owner: String, accepted: Boolean, text: String?): Boolean {
    val entry = byOwner[owner]?.takeIf(::alive) ?: return false
    require((text?.length ?: 0) <= MAX_TEXT)
    val decision = if (accepted) "accepted" else "rejected"
    synchronized(this) { entry.decision = decision }
    append(entry, "person", "decision", text?.trim()?.ifEmpty { null } ?: decision, null)
    return true
  }

  /** Revoke from the owner side: the store forgets the session and both sides see it end. */
  fun revoke(owner: String): Boolean {
    val entry = byOwner[owner] ?: return false
    val removed =
      when (entry.kind) {
        Kind.Card -> cards.revoke(owner)
        Kind.Dashboard -> dashboards.revoke(owner)
      }
    forget<Unit>(entry)
    return removed
  }

  // ---- The agent's side (agent capability) ----

  fun kindOf(agent: String): Kind? = byAgent[agent]?.takeIf(::alive)?.kind

  /** Redeem the code the person's app shows. Single use; returns the agent capability. */
  fun join(code: String, agentName: String?): Joined? {
    val normalized = normalizeCode(code) ?: return null
    val entry =
      synchronized(this) {
        if (throttled()) return null
        val found = codes[normalized]
        if (found == null || found.joinCodeExpires <= clock()) {
          failures.addLast(clock())
          return null
        }
        dropCode(found)
        found.agentName = cleanName(agentName)
        found.agentSeen = clock()
        found.agentStatus = "Connected"
        found
      }
    if (!alive(entry)) return forget(entry)
    entry.bump()
    return Joined(entry.agent, entry.kind, entry.title)
  }

  data class Joined(val session: String, val kind: Kind, val title: String)

  /** An agent asks first: it shows the person [Pending.code] and polls with [Pending.poll]. */
  fun requestAccess(agentName: String?): Pending {
    synchronized(this) {
      expireRequests()
      check(requests.size < MAX_REQUESTS) { "Too many pending agent requests" }
      var code: String
      do code = randomCode() while (code in requests || code in codes)
      val request =
        Request(code, UiBuilderConfig.randomToken(), cleanName(agentName), clock() + REQUEST_MS)
      requests[code] = request
      pollers[request.poll] = request
      return Pending(formatCode(code), request.poll, REQUEST_MS / 1000)
    }
  }

  data class Pending(val code: String, val poll: String, val expiresInSeconds: Long)

  /** Waits for the person to enter the code; null once the request is unknown or expired. */
  suspend fun pollAccess(poll: String, waitMs: Long = maxWaitMs): PollResult? {
    val request = synchronized(this) { pollers[poll] } ?: return null
    if (request.entry == null) {
      if (request.expires <= clock()) {
        synchronized(this) {
          pollers.remove(poll)
          requests.remove(request.code)
        }
        return null
      }
      withTimeoutOrNull(waitMs.coerceIn(0, maxWaitMs)) { request.done.first { it } }
    }
    val entry = request.entry ?: return PollResult(null)
    synchronized(this) { pollers.remove(poll) }
    if (!alive(entry)) return null
    return PollResult(Joined(entry.agent, entry.kind, entry.title))
  }

  data class PollResult(val joined: Joined?)

  /** Record that the agent is active, optionally with what it's doing. Null when gone. */
  fun seen(agent: String, status: String? = null): Kind? {
    val entry = byAgent[agent] ?: return null
    if (!alive(entry)) return forget(entry)
    touch(entry)
    synchronized(this) {
      entry.agentSeen = clock()
      if (status != null) entry.agentStatus = status.trim().take(120)
    }
    entry.bump()
    return entry.kind
  }

  fun agentMessage(agent: String, text: String, kind: String = "message"): Boolean {
    val entry = byAgent[agent]?.takeIf(::alive) ?: return false
    require(text.isNotBlank() && text.length <= MAX_TEXT)
    seen(agent)
    append(entry, "agent", kind, text.trim(), null)
    return true
  }

  /**
   * The person's comments and decisions after [since], waiting up to [waitMs] for one when there
   * are none yet. Null when the session is gone.
   */
  suspend fun awaitFeedback(agent: String, since: Long, waitMs: Long = maxWaitMs): AgentView? {
    var entry = byAgent[agent] ?: return null
    seen(agent, "Waiting for feedback") ?: return null
    fun pending() =
      synchronized(this) { entry.messages.any { it.from == "person" && it.seq > since } }
    if (!pending()) {
      val deadline = clock() + waitMs.coerceIn(0, maxWaitMs)
      while (!pending()) {
        val left = deadline - clock()
        if (left <= 0) break
        val seenVersion = entry.version.value
        withTimeoutOrNull(left) { entry.version.first { it > seenVersion } } ?: break
        entry = byAgent[agent] ?: return null
        if (!alive(entry)) return forget(entry)
      }
    }
    return agentView(agent, since)
  }

  fun agentView(agent: String, since: Long = 0): AgentView? {
    val entry = byAgent[agent] ?: return null
    if (!alive(entry)) return forget(entry)
    val left = touch(entry) ?: return forget(entry)
    return synchronized(this) {
      val now = clock()
      AgentView(
        kind = entry.kind,
        title = entry.title,
        messages = entry.messages.filter { it.seq > since }.takeLast(OWNER_MESSAGES),
        cursor = entry.seq,
        decision = entry.decision,
        personWatching = entry.personSeen > 0 && now - entry.personSeen < WATCHING_MS,
        expiresInSeconds = left,
      )
    }
  }

  // ---- Internals ----

  private fun append(entry: Entry, from: String, kind: String, text: String, target: String?) {
    synchronized(this) {
      entry.seq++
      entry.messages += Message(entry.seq, from, kind, text, target, clock())
      if (entry.messages.size > MAX_MESSAGES) entry.messages.removeAt(0)
    }
    entry.bump()
  }

  private fun presence(entry: Entry, now: Long) =
    Presence(
      joined = entry.agentSeen > 0,
      connected = entry.agentSeen > 0 && now - entry.agentSeen < CONNECTED_MS,
      name = entry.agentName,
      status = entry.agentStatus,
      lastSeenMs = entry.agentSeen.takeIf { it > 0 }?.let { now - it },
    )

  private fun alive(entry: Entry): Boolean =
    when (entry.kind) {
      Kind.Card -> cards.read(entry.owner) != null
      Kind.Dashboard -> dashboards.read(entry.owner) != null
    }

  private fun touch(entry: Entry): Long? =
    when (entry.kind) {
      Kind.Card -> cards.touch(entry.owner)
      Kind.Dashboard -> dashboards.touch(entry.owner)
    }

  /**
   * Drop sessions the stores have expired that nobody came back to. The stores cap live sessions,
   * so sweeping on every registration keeps the relay's indexes (and conversations) bounded too.
   */
  internal fun prune() {
    byOwner.values.filterNot(::alive).forEach { forget<Unit>(it) }
    synchronized(this) { expireRequests() }
  }

  /** Sessions the relay still indexes (for tests). */
  internal val size: Int
    get() = byOwner.size

  private fun <T> forget(entry: Entry): T? {
    byOwner.remove(entry.owner)
    byAgent.remove(entry.agent)
    synchronized(this) { dropCode(entry) }
    entry.bump()
    return null
  }

  private fun issueCode(entry: Entry): String {
    dropCode(entry)
    var code: String
    do code = randomCode() while (code in codes || code in requests)
    codes[code] = entry
    entry.joinCode = formatCode(code)
    entry.joinCodeExpires = clock() + JOIN_CODE_MS
    return entry.joinCode!!
  }

  private fun dropCode(entry: Entry) {
    entry.joinCode?.let { codes.remove(normalizeCode(it)) }
    entry.joinCode = null
    entry.joinCodeExpires = 0
  }

  private fun expireRequests() {
    val now = clock()
    val stale = requests.values.filter { it.expires <= now }
    stale.forEach {
      requests.remove(it.code)
      pollers.remove(it.poll)
    }
  }

  /** A guess budget shared by everyone: codes are short, so wrong ones slow all guessing down. */
  private fun throttled(): Boolean {
    val now = clock()
    while (failures.isNotEmpty() && failures.first() <= now - 60_000) failures.removeFirst()
    return failures.size >= MAX_FAILURES_PER_MINUTE
  }

  companion object {
    const val JOIN_CODE_MS = 15 * 60_000L
    const val REQUEST_MS = 10 * 60_000L
    const val CONNECTED_MS = 90_000L
    const val WATCHING_MS = 60_000L
    const val MAX_TEXT = 2000
    const val MAX_MESSAGES = 200
    const val OWNER_MESSAGES = 50
    const val MAX_REQUESTS = 256
    const val MAX_FAILURES_PER_MINUTE = 30

    // No 0/O, 1/I/L: a code is read aloud or typed from another screen.
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    private fun randomCode() = buildString {
      repeat(8) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    fun formatCode(code: String) = code.take(4) + "-" + code.drop(4)

    /** Upper-cased, separators dropped; null unless it is eight code characters. */
    fun normalizeCode(code: String): String? =
      code
        .uppercase()
        .filter { it.isLetterOrDigit() }
        .takeIf { c -> c.length == 8 && c.all { it in ALPHABET } }

    private fun cleanName(name: String?) =
      name?.filter { it.isLetterOrDigit() || it in " .-_()" }?.trim()?.take(40)?.ifEmpty { null }
        ?: "Agent"
  }
}
