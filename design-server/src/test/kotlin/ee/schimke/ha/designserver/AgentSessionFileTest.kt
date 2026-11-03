package ee.schimke.ha.designserver

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

class AgentSessionFileTest {
  private val baseline = Json.parseToJsonElement("""{"views":[{"title":"Home"}]}""").jsonObject
  private val changed = Json.parseToJsonElement("""{"views":[{"title":"Garden"}]}""").jsonObject

  @Test
  fun aRestartKeepsSessionsCodesDraftsAndTheConversation() = runBlocking {
    val dir = createTempDirectory("agent-sessions").toFile()
    val file = dir.resolve("sessions.json")
    var now = 1_000L
    fun relay() =
      AgentRelay(AgentDesignSessions { now }, DashboardAgentSessions { now }, { now }, 0)

    val before = relay()
    val dash = before.dashboards.create("Tidy", baseline)
    val code = before.register(AgentRelay.Kind.Dashboard, dash.owner, dash.agent, "Tidy")!!
    val card = before.cards.create("Bigger icon", buildJsonObject { put("id", "card-x") })
    val cardCode = before.register(AgentRelay.Kind.Card, card.owner, card.agent, "Bigger icon")!!
    assertTrue(before.cards.putAsset(card.agent, "logo", PNG))
    assertNotNull(before.join(cardCode, "Claude"))
    assertTrue(before.dashboards.submit(dash.agent, 0, changed, "Garden"))
    assertTrue(before.comment(dash.owner, "Bigger camera", "Front door"))
    AgentSessionFile(file, before).save()
    assertEquals(
      setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
      Files.getPosixFilePermissions(file.toPath()),
    )

    // A new process: everything the person and agents had is still there.
    now += 60_000
    val after = relay()
    AgentSessionFile(file, after).load()
    val state = assertNotNull(after.ownerState(dash.owner))
    assertEquals(1, state.revision)
    assertEquals(code, state.joinCode, "an unused code still works after a deploy")
    assertEquals(listOf("Bigger camera"), state.messages.map { it.text })
    assertEquals(changed, after.dashboards.read(dash.agent)?.dashboard)
    assertNotNull(after.join(code, "Codex"))
    val cardState = assertNotNull(after.ownerState(card.owner))
    assertEquals("Claude", cardState.agent.name)
    assertNotNull(after.cards.asset(card.owner, "logo"))

    // Sessions past their lifetime don't come back.
    now += DashboardAgentSessions.MAX_MS
    val later = relay()
    AgentSessionFile(file, later).load()
    assertNull(later.ownerState(dash.owner))
    assertFalse(later.isOwner(card.owner))
  }

  @Test
  fun changesAreSavedOnTheirOwn() = runBlocking {
    val file = createTempDirectory("agent-sessions").toFile().resolve("sessions.json")
    val relay = AgentRelay(AgentDesignSessions(), DashboardAgentSessions())
    val saving = AgentSessionFile(file, relay).start(this, pauseMs = 10)
    try {
      val dash = relay.dashboards.create("Tidy", baseline)
      relay.register(AgentRelay.Kind.Dashboard, dash.owner, dash.agent, "Tidy")
      withTimeout(5_000) { while (!file.isFile || dash.owner !in file.readText()) delay(20) }
      relay.comment(dash.owner, "Bigger", null)
      withTimeout(5_000) { while ("Bigger" !in file.readText()) delay(20) }
    } finally {
      saving.cancel()
    }
  }

  @Test
  fun steadyTrafficCannotPostponeSaving() = runBlocking {
    val file = createTempDirectory("agent-sessions").toFile().resolve("sessions.json")
    val relay = AgentRelay(AgentDesignSessions(), DashboardAgentSessions())
    val saving = AgentSessionFile(file, relay).start(this, pauseMs = 200)
    try {
      val dash = relay.dashboards.create("Tidy", baseline)
      relay.register(AgentRelay.Kind.Dashboard, dash.owner, dash.agent, "Tidy")
      // A change every 50 ms, far more often than the pause, for a second and a half.
      repeat(30) {
        relay.comment(dash.owner, "note $it", null)
        delay(50)
      }
      assertTrue(file.isFile && dash.owner in file.readText(), "saved while traffic continued")
    } finally {
      saving.cancel()
    }
  }

  @Test
  fun anUnreadableFileStartsEmpty() {
    val dir = createTempDirectory("agent-sessions").toFile()
    val file = dir.resolve("sessions.json").apply { writeText("{not json") }
    val relay = AgentRelay(AgentDesignSessions(), DashboardAgentSessions())
    AgentSessionFile(file, relay).load()
    assertTrue(relay.snapshot().isEmpty())
  }

  private companion object {
    // A 1x1 PNG.
    val PNG =
      java.util.Base64.getDecoder()
        .decode(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        )
  }
}
