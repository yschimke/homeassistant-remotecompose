package ee.schimke.ha.designserver

import ee.schimke.livery.sessions.SessionLinks
import ee.schimke.livery.sessions.SessionRelay
import ee.schimke.livery.sessions.server.SessionFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Keeps agent sessions across a design server restart or deploy, so a person and their agent can
 * carry on where they were: Livery's [SessionFile] (one owner-only file on the server's own volume,
 * replaced atomically a moment after each burst of changes), holding the sessions' capabilities and
 * drafts, never a Home Assistant credential. A file written before the design server ran on
 * Livery's session server still loads.
 */
class AgentSessionFile(private val file: File, private val relay: AgentRelay) {
  private val livery = SessionFile(file, relay.server)

  /** The file before Livery's session server: each store's sessions at the top level. */
  @Serializable
  internal data class Legacy(
    val version: Int = 1,
    val cards: List<AgentDesignSessions.Saved> = emptyList(),
    val dashboards: List<DashboardAgentSessions.Saved> = emptyList(),
    val relay: List<SessionRelay.Saved> = emptyList(),
    val links: List<SessionLinks.Saved> = emptyList(),
    val requests: List<SessionRelay.SavedRequest> = emptyList(),
  )

  private val json = Json { ignoreUnknownKeys = true }

  /** Restore what the last run saved. A missing or unreadable file starts empty. */
  fun load() {
    val legacy = runCatching {
      file
        .takeIf { it.isFile }
        ?.readText()
        ?.let { json.parseToJsonElement(it) as? JsonObject }
        ?.takeIf { it["relay"] is JsonArray }
        ?.let { json.decodeFromJsonElement(Legacy.serializer(), it) }
    }
      .onFailure { System.err.println("Agent sessions not restored from $file: ${it.message}") }
      .getOrNull()
    runBlocking {
      if (legacy == null) return@runBlocking livery.load()
      relay.cards.restore(legacy.cards)
      relay.dashboards.restore(legacy.dashboards)
      relay.restore(
        SessionRelay.Snapshot(
          // The oldest files name kinds by their enum ("Card"), not their wire name.
          legacy.relay.map { it.copy(kind = it.kind.lowercase()) },
          legacy.links,
          legacy.requests,
        ),
      )
    }
  }

  fun save() = runBlocking { livery.save() }

  /** Save a moment after each burst of changes, until [scope] ends. */
  fun start(scope: CoroutineScope, pauseMs: Long = 1_000): Job = livery.start(scope, pauseMs)
}
