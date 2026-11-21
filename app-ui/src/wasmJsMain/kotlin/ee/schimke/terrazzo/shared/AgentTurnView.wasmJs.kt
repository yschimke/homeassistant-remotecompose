package ee.schimke.terrazzo.shared

import androidx.compose.runtime.Composable

/** agent-render has no Wasm build yet: the panel draws turns with the shared rows. */
@Composable
internal actual fun AgentTurnView(turn: AgentTurn, agentName: String) =
  SharedAgentTurn(turn, agentName)
