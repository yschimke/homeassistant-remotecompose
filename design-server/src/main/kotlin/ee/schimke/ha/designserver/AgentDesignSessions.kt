package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.validateCardDraft
import kotlinx.serialization.json.JsonObject

/** Short-lived, capability-scoped card drafts. No Home Assistant credentials are held here. */
class AgentDesignSessions(private val clock: () -> Long = System::currentTimeMillis) {
  data class Access(val owner: String, val agent: String)

  data class Snapshot(val instruction: String, val document: JsonObject, val revision: Long)

  private data class Entry(
    val owner: String,
    val agent: String,
    val instruction: String,
    val original: JsonObject,
    val expiresAt: Long,
    var document: JsonObject,
    var revision: Long = 0,
  )

  private val entries = mutableListOf<Entry>()

  @Synchronized
  fun create(instruction: String, document: JsonObject): Access {
    expire()
    require(instruction.isNotBlank() && instruction.length <= 2000)
    check(entries.size < 128) { "Too many pending design sessions" }
    val owner = UiBuilderConfig.randomToken()
    val agent = UiBuilderConfig.randomToken()
    entries += Entry(owner, agent, instruction.trim(), document, clock() + 60 * 60_000, document)
    return Access(owner, agent)
  }

  @Synchronized
  fun read(token: String): Snapshot? {
    expire()
    val entry = entries.firstOrNull { it.owner == token || it.agent == token } ?: return null
    return Snapshot(entry.instruction, entry.document, entry.revision)
  }

  @Synchronized
  fun isAgent(token: String): Boolean {
    expire()
    return entries.any { it.agent == token }
  }

  @Synchronized
  fun readAgent(token: String): Snapshot? {
    expire()
    val entry = entries.firstOrNull { it.agent == token } ?: return null
    return Snapshot(entry.instruction, entry.document, entry.revision)
  }

  /** Accept only an agent's validated update to the revision they read. */
  @Synchronized
  fun submit(token: String, revision: Long, document: JsonObject): Boolean {
    expire()
    val entry = entries.firstOrNull { it.agent == token } ?: return false
    require(revision == entry.revision) { "The design changed; read it again" }
    validateCardDraft(entry.original, document)
    entry.document = document
    entry.revision++
    return true
  }

  @Synchronized
  fun revoke(owner: String): Boolean {
    expire()
    return entries.removeIf { it.owner == owner }
  }

  private fun expire() {
    val now = clock()
    entries.removeIf { it.expiresAt <= now }
  }
}
