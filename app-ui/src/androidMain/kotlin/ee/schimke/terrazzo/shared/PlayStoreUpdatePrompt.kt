package ee.schimke.terrazzo.shared

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Starts Google Play's own update consent flow when this installed track has a newer version and
 * [shouldStartPlayUpdate] says it is time: at once for testers ([isPlayUpdateTester]), otherwise
 * once the update has been on Play for a week.
 *
 * The Play service answers "no update" for sideloaded/debug installs, so this remains quiet during
 * development. Immediate updates are used deliberately: both the phone and watch apps are
 * companions and must keep their shared wire protocol compatible.
 */
fun ComponentActivity.promptForPlayStoreUpgrade() {
  val manager = AppUpdateManagerFactory.create(applicationContext)
  val launcher =
    registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
      /* Play owns UI */
    }
  checkForPlayStoreUpgrade(manager, launcher, resumeOnly = false)
  // An immediate update interrupted in the background resumes when the app returns, as Play's
  // immediate-flow guidance does; new updates are only offered once per launch.
  lifecycle.addObserver(
    LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME)
        checkForPlayStoreUpgrade(manager, launcher, resumeOnly = true)
    }
  )
}

private fun ComponentActivity.checkForPlayStoreUpgrade(
  manager: AppUpdateManager,
  launcher: ActivityResultLauncher<IntentSenderRequest>,
  resumeOnly: Boolean,
) {
  val tester = isPlayUpdateTester(applicationContext)
  // Activity-scoped: Play's answer is dropped once this activity stops, so a stale reply can't
  // launch a launcher that was unregistered when the activity was destroyed.
  manager.appUpdateInfo.addOnSuccessListener(this) { info ->
    val start =
      shouldStartPlayUpdate(
        available =
          !resumeOnly &&
            info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
            info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
        inProgress =
          info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS,
        tester = tester,
        stalenessDays = info.clientVersionStalenessDays(),
      )
    if (start) {
      manager.startUpdateFlowForResult(
        info,
        launcher,
        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
      )
    }
  }
}

private const val PREFS = "play_updates"
private const val KEY_TESTER = "tester"

/** Whether this device opted in to Play updates as soon as they appear (internal testers). */
fun isPlayUpdateTester(context: Context): Boolean =
  context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TESTER, false)

fun setPlayUpdateTester(context: Context, tester: Boolean) {
  context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_TESTER, tester) }
}
