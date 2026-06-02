package ee.schimke.terrazzo.core.session

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped (Read | Write) holder for the dashboard chip. Survives Activity recreation
 * (rotation, theme change) and resume, but resets on cold start so a fresh launch is gated by
 * `PreferencesStore`'s `permanentWriteMode` — Read on the first composition unless the user has
 * opted in to permanent Write.
 *
 * The state is nullable so DashboardsRoot can distinguish "the user has already chosen for this
 * process" from "use the permanent-write-mode default". Once the chip is tapped the value is
 * non-null and sticks for the rest of the process.
 */
@SingleIn(AppScope::class)
@Inject
class SessionWriteMode {
  private val _writeMode = MutableStateFlow<Boolean?>(null)
  val writeMode: StateFlow<Boolean?> = _writeMode.asStateFlow()

  fun set(writeMode: Boolean) {
    _writeMode.value = writeMode
  }
}
