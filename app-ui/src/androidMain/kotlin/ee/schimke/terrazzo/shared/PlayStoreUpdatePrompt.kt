package ee.schimke.terrazzo.shared

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Starts Google Play's own update consent flow when this installed track has a newer version.
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
  checkForPlayStoreUpgrade(manager, launcher)
}

private fun ComponentActivity.checkForPlayStoreUpgrade(
  manager: AppUpdateManager,
  launcher:
    androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>,
) {
  manager.appUpdateInfo.addOnSuccessListener { info ->
    val canStart =
      info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
        info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
    val resume =
      info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
    if (canStart || resume) {
      manager.startUpdateFlowForResult(
        info,
        launcher,
        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
      )
    }
  }
}
