package ee.schimke.ha.designserver

import kotlinx.serialization.json.*

/** A temporary dashboard draft exchange; it never stores an HA credential or applies a draft. */
class DashboardAgentSessions(private val clock: () -> Long = System::currentTimeMillis) {
  data class Access(val owner: String, val agent: String)

  data class Snapshot(
    val request: String,
    val dashboard: JsonObject,
    val revision: Long,
    val summary: String,
    val notes: List<String>,
  )

  private data class Entry(
    val owner: String,
    val agent: String,
    val request: String,
    val expires: Long,
    var dashboard: JsonObject,
    var revision: Long = 0,
    var summary: String = "",
    var notes: List<String> = emptyList(),
  )

  private val entries = mutableListOf<Entry>()

  @Synchronized
  fun create(request: String, dashboard: JsonObject): Access {
    expire()
    require(request.isNotBlank() && request.length <= 2000)
    validate(dashboard)
    check(entries.size < 128)
    val owner = UiBuilderConfig.randomToken()
    val agent = UiBuilderConfig.randomToken()
    entries += Entry(owner, agent, request.trim(), clock() + 60 * 60_000, dashboard)
    return Access(owner, agent)
  }

  @Synchronized
  fun read(token: String, agentOnly: Boolean = false): Snapshot? {
    expire()
    val entry =
      entries.firstOrNull { it.agent == token || (!agentOnly && it.owner == token) } ?: return null
    return Snapshot(entry.request, entry.dashboard, entry.revision, entry.summary, entry.notes)
  }

  @Synchronized
  fun note(token: String, text: String): Boolean {
    expire()
    val entry = entries.firstOrNull { it.owner == token } ?: return false
    require(text.isNotBlank() && text.length <= 2000)
    entry.notes = (entry.notes + text.trim()).takeLast(50)
    return true
  }

  @Synchronized
  fun submit(token: String, revision: Long, dashboard: JsonObject, summary: String): Boolean {
    expire()
    val entry = entries.firstOrNull { it.agent == token } ?: return false
    require(revision == entry.revision && summary.length <= 2000)
    validate(dashboard)
    entry.dashboard = dashboard
    entry.summary = summary.trim()
    entry.revision++
    return true
  }

  @Synchronized
  fun revoke(token: String): Boolean {
    expire()
    return entries.removeIf { it.owner == token }
  }

  private fun expire() {
    entries.removeIf { it.expires <= clock() }
  }

  private fun validate(dashboard: JsonObject) {
    require(dashboard.toString().length <= 512 * 1024)
    val views = dashboard["views"] as? JsonArray
    requireNotNull(views) { "A dashboard needs views" }
    require(views.all { it is JsonObject })
  }
}
