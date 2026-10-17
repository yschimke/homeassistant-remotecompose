package ee.schimke.ha.client.dashboard

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class DashboardEditSessionTest {
  private fun config(title: String) = buildJsonObject {
    put("title", title)
    put("views", JsonArray(emptyList()))
  }

  @Test
  fun saveAndUndoPreserveCompleteUnknownConfig() = runTest {
    val initial =
      Json.parseToJsonElement("""{"title":"old","unknown":{"custom":[1,null]},"views":[]}""")
        .jsonObject
    var remote = initial
    val edits =
      DashboardEditSession(initial) { expected, replacement ->
        if (remote != expected) throw DashboardConflictException()
        remote = replacement
      }
    val changed = JsonObject(initial + ("title" to JsonPrimitive("new")))
    edits.edit(changed)
    edits.save()
    assertEquals(changed, remote)
    assertTrue(edits.canUndoSave)
    edits.undoSave()
    assertEquals(initial, remote)
    assertEquals(initial, edits.draft)
    assertFalse(edits.canUndoSave)
  }

  @Test
  fun externalChangesRefuseBothSaveAndUndoWithoutLosingDraft() = runTest {
    var remote = config("initial")
    var writes = 0
    val edits =
      DashboardEditSession(remote) { expected, replacement ->
        if (remote != expected) throw DashboardConflictException()
        writes++
        remote = replacement
      }
    val draft = config("draft")
    edits.edit(draft)
    remote = config("someone else")
    assertFailsWith<DashboardConflictException> { edits.save() }
    assertEquals(draft, edits.draft)
    assertEquals(0, writes)
    remote = config("initial")
    edits.save()
    remote = config("changed after save")
    assertFailsWith<DashboardConflictException> { edits.undoSave() }
    assertEquals(config("changed after save"), remote)
    assertTrue(edits.canUndoSave)
    assertEquals(1, writes)
  }

  @Test
  fun failedSaveCanBeRetriedAndUndoKeepsLaterLocalEdits() = runTest {
    val initial = config("initial")
    var fail = true
    var remote = initial
    val edits =
      DashboardEditSession(initial) { expected, replacement ->
        assertEquals(remote, expected)
        if (fail) error("connection lost")
        remote = replacement
      }
    edits.edit(config("first"))
    assertFailsWith<IllegalStateException> { edits.save() }
    assertFalse(edits.canUndoSave)
    fail = false
    edits.save()
    edits.edit(config("later draft"))
    edits.undoSave()
    assertEquals(config("later draft"), edits.draft)
    assertEquals(initial, remote)
  }

  @Test
  fun yamlAndNonAdminSessionsCanDraftButNeverWrite() = runTest {
    for (reason in
      listOf(
        "YAML dashboards support draft export only",
        "An administrator must save this dashboard",
      )) {
      val edits = DashboardEditSession(config("initial"), reason) { _, _ -> fail("must not write") }
      edits.edit(config("draft"))
      assertEquals(config("draft"), edits.draft)
      assertFailsWith<IllegalStateException> { edits.save() }
      assertFailsWith<IllegalStateException> { edits.undoSave() }
    }
  }
}
