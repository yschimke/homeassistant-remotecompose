package ee.schimke.terrazzo.shared

import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Temporary capabilities are scoped to this browser tab and expire with the server session. */
@Serializable
internal data class SavedCardAgentSession(
  val server: String,
  val ownerToken: String,
  val agentToken: String,
  val instruction: String,
  val expiresAt: Long,
)

internal expect fun readCardAgentSessions(): String?

internal expect fun writeCardAgentSessions(value: String?)

internal object CardAgentSessions {
  private var memory = emptyMap<String, SavedCardAgentSession>()

  private fun all(): Map<String, SavedCardAgentSession> =
    readCardAgentSessions()?.let {
      runCatching { Json.decodeFromString<Map<String, SavedCardAgentSession>>(it) }.getOrNull()
    } ?: memory

  fun get(
    key: String,
    now: Long = Clock.System.now().toEpochMilliseconds(),
  ): SavedCardAgentSession? {
    val value = all()[key] ?: return null
    if (value.expiresAt <= now) {
      remove(key)
      return null
    }
    return value
  }

  fun put(key: String, value: SavedCardAgentSession) = save(all() + (key to value))

  fun remove(key: String) = save(all() - key)

  fun clear() = save(emptyMap())

  private fun save(values: Map<String, SavedCardAgentSession>) {
    memory = values
    writeCardAgentSessions(if (values.isEmpty()) null else Json.encodeToString(values))
  }
}
