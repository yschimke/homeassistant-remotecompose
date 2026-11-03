package ee.schimke.ha.designserver

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Keeps agent sessions across a design server restart or deploy, so a person and their agent can
 * carry on where they were. Everything lives in one file on the server's own volume, readable by
 * the server alone: it holds the sessions' capabilities and drafts, never a Home Assistant
 * credential. Writes follow changes after a short pause and replace the file atomically; expired
 * sessions are dropped on the way in and out.
 */
class AgentSessionFile(private val file: File, private val relay: AgentRelay) {
  @Serializable
  data class Contents(
    val version: Int = 1,
    val cards: List<AgentDesignSessions.Saved> = emptyList(),
    val dashboards: List<DashboardAgentSessions.Saved> = emptyList(),
    val relay: List<AgentRelay.Saved> = emptyList(),
    val links: List<AgentLinks.Saved> = emptyList(),
  )

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  /** Restore what the last run saved. A missing or unreadable file starts empty. */
  fun load() {
    val contents =
      runCatching {
        file.takeIf { it.isFile }?.readText()?.let { json.decodeFromString<Contents>(it) }
      }
        .onFailure { System.err.println("Agent sessions not restored from $file: ${it.message}") }
        .getOrNull() ?: return
    relay.cards.restore(contents.cards)
    relay.dashboards.restore(contents.dashboards)
    relay.links.restore(contents.links)
    relay.restore(contents.relay)
  }

  @Synchronized
  fun save() {
    val contents =
      Contents(
        cards = relay.cards.snapshot(),
        dashboards = relay.dashboards.snapshot(),
        relay = relay.snapshot(),
        links = relay.links.snapshot(),
      )
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, file.name + ".tmp")
    temp.writeText(json.encodeToString(Contents.serializer(), contents))
    runCatching {
      Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rw-------"))
    }
    Files.move(
      temp.toPath(),
      file.toPath(),
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE,
    )
  }

  /** Save a moment after each burst of changes, until [scope] ends. */
  fun start(scope: CoroutineScope, pauseMs: Long = 1_000): Job {
    // Compare with the count now, not with the first value collected: a change made before the
    // collector subscribes would otherwise be taken for the starting point and never saved.
    val loaded = relay.changes.value
    return scope.launch(Dispatchers.IO) {
      // Not collectLatest: steady traffic would restart the pause forever and never save. Each
      // burst is saved at most [pauseMs] after its first change; changes meanwhile are conflated.
      relay.changes
        .filter { it != loaded }
        .conflate()
        .collect {
          delay(pauseMs)
          runCatching { save() }
            .onFailure { System.err.println("Agent sessions not saved to $file: ${it.message}") }
        }
    }
  }
}
