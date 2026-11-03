package ee.schimke.terrazzo.shared

import ee.schimke.ha.client.agent.AgentGrantStore
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Temporary capabilities, kept in this browser's local storage (memory on Android and desktop,
 * whose process outlives a screen) so a reload resumes the session. Never in a synced store such as
 * Wasmo's settings: a capability is a secret for this device, and it ends with its session.
 */
@Serializable
internal data class SavedCardAgentSession(
  val server: String,
  val ownerToken: String,
  val instruction: String,
  val expiresAt: Long,
)

internal expect fun readAgentGrants(key: String): String?

internal expect fun writeAgentGrants(key: String, value: String?)

private const val CARD_SESSIONS_KEY = "terrazzo.cardAgentSessions"

/** Dashboard edit sessions' owner capabilities, for [CloudDashboardAgentEdits]. */
internal fun dashboardAgentGrants(): AgentGrantStore =
  object : AgentGrantStore {
    private var memory: String? = null

    override fun load() = readAgentGrants(KEY) ?: memory

    override fun save(value: String?) {
      memory = value
      writeAgentGrants(KEY, value)
    }
  }

private const val KEY = "terrazzo.dashboardAgentGrants"

private val json = Json { ignoreUnknownKeys = true }

internal object CardAgentSessions {
  private var memory = emptyMap<String, SavedCardAgentSession>()

  private fun all(): Map<String, SavedCardAgentSession> =
    readAgentGrants(CARD_SESSIONS_KEY)?.let {
      runCatching { json.decodeFromString<Map<String, SavedCardAgentSession>>(it) }.getOrNull()
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
    writeAgentGrants(CARD_SESSIONS_KEY, if (values.isEmpty()) null else Json.encodeToString(values))
  }
}
