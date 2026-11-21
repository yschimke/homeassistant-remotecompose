package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import com.zhangke.compose.agent.render.AgentOutput
import com.zhangke.compose.agent.render.model.AgentCompleteMetaDataUiModel
import com.zhangke.compose.agent.render.model.AgentOutput as Output
import com.zhangke.compose.agent.render.model.ToolStatus
import com.zhangke.compose.agent.render.theme.AgentColorScheme
import com.zhangke.compose.agent.render.theme.AgentRenderTheme
import kotlin.time.Instant

/**
 * An agent turn drawn with agent-render: its steps as tool calls, folded under a summary once the
 * reply (the turn's final result, drawn as Markdown) has come.
 */
@Composable
internal actual fun AgentTurnView(turn: AgentTurn, agentName: String) {
  val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
  // The steps carry no time of their own here; agent-render only orders by the list.
  val at = Instant.fromEpochSeconds(0)
  val outputs = buildList {
    turn.steps.forEach { step ->
      add(
        Output.ToolCall(
          id = "step-${step.seq}",
          name = stepTitle(step),
          arguments = step.arguments.orEmpty(),
          output = step.text,
          status = if (step.ok) ToolStatus.Success else ToolStatus.Error,
          createAt = at,
        ),
      )
    }
    turn.reply?.let {
      add(
        Output.AssistantText(
          id = "reply-${it.seq}",
          content = it.text,
          createAt = at,
          completed = true,
          isFinalResult = true,
        ),
      )
    }
  }
  Column(Modifier.fillMaxWidth()) {
    Text(
      agentName.ifBlank { "Agent" },
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AgentRenderTheme(
      colorScheme = if (dark) AgentColorScheme.dark() else AgentColorScheme.light(),
    ) {
      AgentOutput(
        modifier = Modifier.fillMaxWidth(),
        outputList = outputs,
        completed = turn.reply != null,
        completeMetaDataUiModel =
          AgentCompleteMetaDataUiModel(toolcallTimes = turn.steps.size.takeIf { it > 0 }),
      )
    }
  }
}
