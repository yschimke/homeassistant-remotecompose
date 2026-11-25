package ee.schimke.ha.client.dashboard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What one turn of the dashboard chat (`terrazzo/assist`) answers: Home Assistant's conversation
 * agent, asked from an open dashboard.
 */
@Serializable
data class DashboardChatReply(
  /** The agent's words; a stand-in when it only drafted. */
  val speech: String = "",
  /** Whether the agent failed (its provider refused or was out of reach); [speech] says why. */
  val error: Boolean = false,
  /** The conversation to carry on in the next turn. */
  @SerialName("conversation_id") val conversationId: String? = null,
  /** The edit session holding the agent's draft, drawn in place on the dashboard. */
  @SerialName("session_id") val sessionId: String? = null,
  /** Whether this turn submitted a draft. */
  val drafted: Boolean = false,
  /** The dashboard tools it called, in words. Assist's own tools are not listed. */
  val steps: List<DashboardChatStep> = emptyList(),
) {
  companion object {
    /** Entities on screen a turn names to the agent; Home Assistant keeps at most this many. */
    const val MAX_ENTITIES = 60
  }
}

@Serializable
data class DashboardChatStep(
  val tool: String,
  val input: String = "",
  val output: String = "",
  val ok: Boolean = true,
)

/** One line of the dashboard chat, as the app shows it. */
data class DashboardChatLine(val fromPerson: Boolean, val text: String, val error: Boolean = false)
