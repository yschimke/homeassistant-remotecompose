package ee.schimke.terrazzo.wear.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * An update starts the watch's template store afresh (its templates are scoped to the build), so
 * warm the pinned cards as soon as the update lands rather than when the app is next opened.
 */
class WearPackageReplacedReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
      try {
        WearCardWarmup.run(context.applicationContext)
      } finally {
        pending.finish()
      }
    }
  }
}
