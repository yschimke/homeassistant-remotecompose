package ee.schimke.ha.client.dashboard

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * A host-owned edit transaction. Neither the editor nor the design server receives HA credentials.
 */
class DashboardEditSession(
  original: JsonObject,
  /** YAML dashboards and non-admin sessions can edit/export drafts but cannot write HA. */
  val readOnlyReason: String? = null,
  private val replace: suspend (expected: JsonObject, replacement: JsonObject) -> Unit,
) {
  private val mutex = Mutex()
  private var baseline = original
  private var previous: JsonObject? = null
  var draft: JsonObject = original
    private set

  val canUndoSave: Boolean
    get() = previous != null

  fun edit(config: JsonObject) {
    draft = config
  }

  /** A failed write or conflict leaves the baseline, draft and undo intact for recovery/export. */
  suspend fun save() = mutex.withLock {
    check(readOnlyReason == null) { readOnlyReason.orEmpty() }
    val candidate = draft
    if (candidate == baseline) return@withLock
    replace(baseline, candidate)
    previous = baseline
    baseline = candidate
  }

  /** Undo is a conditional write too: a subsequent edit from another client must never be lost. */
  suspend fun undoSave() = mutex.withLock {
    check(readOnlyReason == null) { readOnlyReason.orEmpty() }
    val before = previous ?: error("There is no saved change to undo")
    val saved = baseline
    replace(saved, before)
    baseline = before
    previous = null
    // Preserve edits made locally since saving.
    if (draft == saved) draft = before
  }
}

class DashboardConflictException :
  IllegalStateException(
    "This dashboard changed in Home Assistant. Export your draft, reload the dashboard and reapply your changes."
  )
