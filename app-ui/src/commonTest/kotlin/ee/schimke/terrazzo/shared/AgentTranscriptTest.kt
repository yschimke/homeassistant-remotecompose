package ee.schimke.terrazzo.shared

import ee.schimke.ha.client.agent.AgentRelayMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentTranscriptTest {
  private fun person(seq: Long, text: String) = AgentRelayMessage(seq, "person", "comment", text)

  private fun step(seq: Long, tool: String) =
    AgentRelayMessage(seq, "agent", AgentRelayMessage.TOOL, "ok", tool = tool)

  private fun reply(seq: Long, text: String) = AgentRelayMessage(seq, "agent", "message", text)

  @Test
  fun stepsBelongToTheTurnTheirReplyEnds() {
    val items =
      chatItems(
        listOf(
          person(1, "Add soil"),
          step(2, "find_entities"),
          step(3, "submit_draft"),
          reply(4, "Done"),
          person(5, "Bigger"),
          step(6, "submit_draft"),
        ),
      )
    assertEquals(4, items.size)
    val first = (items[1] as ChatItem.Turn).turn
    assertEquals(listOf(2L, 3L), first.steps.map { it.seq })
    assertEquals("Done", first.reply?.text)
    // Still working: steps, no reply yet.
    val last = (items[3] as ChatItem.Turn).turn
    assertEquals(listOf(6L), last.steps.map { it.seq })
    assertEquals(null, last.reply)
  }

  @Test
  fun aReplyWithNoStepsIsATurnAndInterruptedStepsEndTheirs() {
    val items = chatItems(listOf(reply(1, "Hello"), step(2, "home_overview"), person(3, "Wait")))
    assertEquals(listOf(true, true, false), items.map { it is ChatItem.Turn })
    assertEquals(null, (items[1] as ChatItem.Turn).turn.reply)
  }

  @Test
  fun toolsReadAsWords() {
    assertEquals("Find entities", stepTitle(step(1, "find_entities")))
  }
}
