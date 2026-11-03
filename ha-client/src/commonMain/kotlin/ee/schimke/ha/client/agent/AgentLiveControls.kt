package ee.schimke.ha.client.agent

import kotlinx.coroutines.flow.StateFlow

/**
 * The live controls an edit session offers when it runs through the design server's agent relay:
 * the code that lets an agent in, who is connected, and the conversation. Sessions without the
 * relay (an older server, or an agent signed in to Home Assistant directly) don't offer them.
 */
interface AgentLiveControls {
  /** The design server the relay runs on. */
  val relayServer: String

  /** The relay state for [sessionId], updated while that session is watched; null before then. */
  fun live(sessionId: String): StateFlow<AgentRelayState?>

  suspend fun newJoinCode(sessionId: String)

  /** Let in the agent that showed the person [code]. */
  suspend fun claim(sessionId: String, code: String)

  /** Say something to the agent, optionally about one card ([target]). */
  suspend fun comment(sessionId: String, text: String, target: String? = null)
}

/**
 * Where owner capabilities are kept between runs: the browser's local storage on the web, so a
 * reload or a return to the tab resumes the session. Never a synced store: a capability is a secret
 * for this device only, and it expires with its session (at most a day).
 */
interface AgentGrantStore {
  fun load(): String?

  fun save(value: String?)

  companion object {
    fun memory(): AgentGrantStore =
      object : AgentGrantStore {
        private var value: String? = null

        override fun load() = value

        override fun save(value: String?) {
          this.value = value
        }
      }
  }
}
