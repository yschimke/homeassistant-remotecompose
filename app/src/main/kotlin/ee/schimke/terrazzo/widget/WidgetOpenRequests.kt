package ee.schimke.terrazzo.widget

import android.content.Context
import android.content.Intent
import ee.schimke.ha.rc.components.HaAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a launcher widget's more-info or navigate tap asked the app to open: the entity's details,
 * or a dashboard path (`/lovelace/kitchen`). The tap launches the app with the target as an extra
 * ([launchIntent]); the activity hands it here ([offer]) on a cold start and on a new intent, and
 * the dashboard screen opens it once ([consume]), as the card's own tap would in the app.
 */
internal object WidgetOpenRequests {

  sealed interface Target {
    data class MoreInfo(val entityId: String) : Target

    data class Navigate(val path: String) : Target
  }

  private val pending = MutableStateFlow<Target?>(null)

  /** The target waiting to be opened, if any. */
  val requests: StateFlow<Target?> = pending.asStateFlow()

  /** The app's launch intent carrying [action]'s target; null for any other action. */
  fun launchIntent(context: Context, action: HaAction): Intent? {
    val (key, value) =
      when (action) {
        is HaAction.MoreInfo -> EXTRA_MORE_INFO to action.entityId
        is HaAction.Navigate -> EXTRA_NAVIGATE to action.path
        else -> return null
      }
    val launch =
      context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
    return launch.putExtra(key, value)
  }

  /** Records [intent]'s target, if it carries one. */
  fun offer(intent: Intent?) {
    targetOf(intent)?.let { pending.value = it }
  }

  /** Clears [target] once opened, unless a newer tap already replaced it. */
  fun consume(target: Target) {
    pending.compareAndSet(target, null)
  }

  internal fun targetOf(intent: Intent?): Target? {
    intent ?: return null
    intent
      .getStringExtra(EXTRA_MORE_INFO)
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return Target.MoreInfo(it)
      }
    // Only an app-relative path: the dashboard resolves it, or opens it on the instance.
    return intent
      .getStringExtra(EXTRA_NAVIGATE)
      ?.takeIf { it.startsWith("/") }
      ?.let(Target::Navigate)
  }

  const val EXTRA_MORE_INFO = "ee.schimke.terrazzo.widget.OPEN_MORE_INFO"
  const val EXTRA_NAVIGATE = "ee.schimke.terrazzo.widget.OPEN_NAVIGATE"
}
