package ee.schimke.ha.designserver

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/**
 * A person's lasting connection to their agent: set up once (the agent redeems a code, or the
 * person enters the agent's), then every card or dashboard request the person starts is delivered
 * to that agent, which listens with `await_request`. Nothing is copied after the setup.
 *
 * A link holds no Home Assistant credential and grants nothing by itself: each delivered request
 * carries its own session capability, scoped to that one card or dashboard. A link ends 30 days
 * after either side last used it, or when the person disconnects it.
 */
class AgentLinks(
  private val clock: () -> Long = System::currentTimeMillis,
  private val maxWaitMs: Long = 25_000,
  private val changed: () -> Unit = {},
) {
  /** A request delivered to the linked agent: the session to work in. */
  @Serializable
  data class Delivery(val seq: Long, val session: String, val kind: String, val title: String)

  data class Created(val owner: String, val code: String)

  /** What the person's Settings show. */
  data class State(
    val version: Long,
    val connected: Boolean,
    val name: String,
    val listening: Boolean,
    val lastSeenMs: Long?,
    val code: String?,
    val codeExpiresInSeconds: Long,
  )

  data class Inbox(val requests: List<Delivery>, val cursor: Long)

  private inner class Link(val owner: String, val agent: String) {
    val version = MutableStateFlow(1L)
    var connected = false
    var name = "Agent"
    var agentSeen = 0L
    var activeAt = clock()
    var code: String? = null
    var codeExpires = 0L
    var seq = 0L
    val inbox = ArrayList<Delivery>()

    fun bump() {
      activeAt = clock()
      version.update { it + 1 }
      changed()
    }
  }

  private val byOwner = ConcurrentHashMap<String, Link>()
  private val byAgent = ConcurrentHashMap<String, Link>()
  private val codes = HashMap<String, Link>()

  /** Start the setup: a link waiting for its agent, and the code to give it. */
  fun create(): Created {
    prune()
    check(byOwner.size < MAX_LINKS) { "Too many agent links" }
    val link = Link(UiBuilderConfig.randomToken(), UiBuilderConfig.randomToken())
    byOwner[link.owner] = link
    byAgent[link.agent] = link
    val code = synchronized(this) { issueCode(link) }
    link.bump()
    return Created(link.owner, code)
  }

  fun isOwner(owner: String) = owner.isNotEmpty() && live(byOwner[owner]) != null

  /** Redeem a link code ([AgentRelay.normalizeCode] form): the agent's link capability. */
  fun connect(code: String, agentName: String?): String? {
    val link = synchronized(this) { codes.remove(code)?.takeIf { it.codeExpires > clock() } }
    return link?.let { attach(it, agentName) }
  }

  /** The person entered the code an agent showed them (`request_access`): that agent's link. */
  fun attachAgent(owner: String, agentName: String?): String? =
    live(byOwner[owner])?.let { attach(it, agentName) }

  private fun attach(link: Link, agentName: String?): String {
    synchronized(this) {
      dropCode(link)
      link.connected = true
      link.name = AgentRelay.cleanName(agentName)
      link.agentSeen = clock()
    }
    link.bump()
    return link.agent
  }

  /** The person's view of their link, now or once it changes past [since]. */
  suspend fun state(owner: String, since: Long? = null): State? {
    val link = live(byOwner[owner]) ?: return null
    if (since != null && link.version.value <= since)
      withTimeoutOrNull(maxWaitMs) { link.version.first { it > since } }
    if (live(byOwner[owner]) == null) return null
    return synchronized(this) {
      val now = clock()
      link.activeAt = now
      if (link.codeExpires <= now) dropCode(link)
      State(
        version = link.version.value,
        connected = link.connected,
        name = link.name,
        listening = link.agentSeen > 0 && now - link.agentSeen < AgentRelay.CONNECTED_MS,
        lastSeenMs = link.agentSeen.takeIf { it > 0 }?.let { now - it },
        code = link.code,
        codeExpiresInSeconds = ((link.codeExpires - now) / 1000).coerceAtLeast(0),
      )
    }
  }

  /** A fresh code, to connect another agent in place of this one. */
  fun newCode(owner: String): String? {
    val link = live(byOwner[owner]) ?: return null
    return synchronized(this) { issueCode(link) }.also { link.bump() }
  }

  /** The linked agent's name when [owner] names a connected link: where a new request goes. */
  fun deliver(owner: String, session: String, kind: AgentRelay.Kind, title: String): String? {
    val link = live(byOwner[owner])?.takeIf { it.connected } ?: return null
    synchronized(this) {
      link.seq++
      link.inbox += Delivery(link.seq, session, kind.wire, title)
      if (link.inbox.size > MAX_INBOX) link.inbox.removeAt(0)
    }
    link.bump()
    return link.name
  }

  fun listening(owner: String): Boolean {
    val link = live(byOwner[owner]) ?: return false
    return link.agentSeen > 0 && clock() - link.agentSeen < AgentRelay.CONNECTED_MS
  }

  /**
   * The agent's side: requests delivered after [since], waiting up to [maxWaitMs] for one. Each
   * call counts as listening, which the person sees. Null once the link is gone.
   */
  suspend fun awaitRequests(agent: String, since: Long): Inbox? {
    val link = live(byAgent[agent]) ?: return null
    fun seen() =
      synchronized(this) {
        link.agentSeen = clock()
        link.activeAt = link.agentSeen
      }
    seen()
    link.bump()
    val deadline = clock() + maxWaitMs
    while (synchronized(this) { link.inbox.none { it.seq > since } }) {
      val left = deadline - clock()
      if (left <= 0) break
      val version = link.version.value
      withTimeoutOrNull(left) { link.version.first { it > version } } ?: break
      if (live(byAgent[agent]) == null) return null
    }
    seen()
    return synchronized(this) { Inbox(link.inbox.filter { it.seq > since }, link.seq) }
  }

  fun revoke(owner: String): Boolean {
    val link = byOwner.remove(owner) ?: return false
    byAgent.remove(link.agent)
    synchronized(this) { dropCode(link) }
    link.bump()
    return true
  }

  // ---- Saved across restarts ([AgentSessionFile]) ----

  @Serializable
  data class Saved(
    val owner: String,
    val agent: String,
    val connected: Boolean,
    val name: String,
    val agentSeen: Long,
    val activeAt: Long,
    val code: String? = null,
    val codeExpires: Long = 0,
    val seq: Long = 0,
    val inbox: List<Delivery> = emptyList(),
  )

  fun snapshot(): List<Saved> =
    synchronized(this) {
      byOwner.values.map {
        Saved(
          it.owner,
          it.agent,
          it.connected,
          it.name,
          it.agentSeen,
          it.activeAt,
          it.code,
          it.codeExpires,
          it.seq,
          it.inbox.toList(),
        )
      }
    }

  fun restore(saved: List<Saved>) {
    for (s in saved) {
      if (s.activeAt + IDLE_MS <= clock()) continue
      val link = Link(s.owner, s.agent)
      synchronized(this) {
        link.connected = s.connected
        link.name = s.name
        link.agentSeen = s.agentSeen
        link.activeAt = s.activeAt
        link.seq = s.seq
        link.inbox += s.inbox
        s.code
          ?.takeIf { s.codeExpires > clock() }
          ?.let { code ->
            AgentRelay.normalizeCode(code)?.let { codes[it] = link }
            link.code = code
            link.codeExpires = s.codeExpires
          }
      }
      byOwner[s.owner] = link
      byAgent[s.agent] = link
    }
  }

  // ---- Internals ----

  private fun live(link: Link?): Link? {
    link ?: return null
    if (link.activeAt + IDLE_MS > clock()) return link
    revoke(link.owner)
    return null
  }

  private fun prune() {
    byOwner.values.toList().forEach { live(it) }
  }

  /** Whether [code] is an unused link code; codes share one format with sessions'. */
  fun hasCode(code: String) = synchronized(this) { code in codes }

  private fun issueCode(link: Link): String {
    dropCode(link)
    var code: String
    do code = AgentRelay.randomCode() while (code in codes)
    codes[code] = link
    link.code = AgentRelay.formatCode(code)
    link.codeExpires = clock() + AgentRelay.JOIN_CODE_MS
    return link.code!!
  }

  private fun dropCode(link: Link) {
    link.code?.let { codes.remove(AgentRelay.normalizeCode(it)) }
    link.code = null
    link.codeExpires = 0
  }

  companion object {
    const val IDLE_MS = 30L * 24 * 60 * 60_000
    const val MAX_LINKS = 4096
    const val MAX_INBOX = 50
  }
}
