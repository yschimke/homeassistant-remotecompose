package ee.schimke.terrazzo.wearsync

import android.app.Application
import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.data.AppHelperResultCode
import com.google.android.horologist.data.WearDataLayerRegistry
import com.google.android.horologist.datalayer.phone.PhoneDataLayerAppHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A watch connected to this phone, and whether the Terrazzo watch app is on it. */
data class PairedWatch(val nodeId: String, val displayName: String, val appInstalled: Boolean)

/**
 * Cross-device install, via Horologist's [PhoneDataLayerAppHelper].
 *
 * Horologist identifies an installed peer by a statically declared capability: the watch app
 * advertises `data_layer_app_helper_device_watch` (`wear/src/main/res/values/wear.xml`) and this
 * app `data_layer_app_helper_device_phone` (`app/src/main/res/values/wear.xml`). A connected node
 * without the watch capability is a watch that can take the app, and [installOnWatch] opens its
 * Play Store listing there — the phone and watch apps share the `ee.schimke.harc` listing.
 *
 * Watch builds from before the Horologist capability only advertise
 * [WearCapabilityProbe.CAPABILITY] (and only on Wear widget capable watches), so that counts as
 * installed too: a user on an older watch build isn't asked to install what they already have.
 */
@OptIn(ExperimentalHorologistApi::class)
class WearAppInstallHelper(context: Context, scope: CoroutineScope) {

  private val registry: WearDataLayerRegistry by lazy {
    WearDataLayerRegistry.fromContext(
      application = context.applicationContext as Application,
      coroutineScope = scope,
    )
  }

  private val appHelper: PhoneDataLayerAppHelper by lazy {
    PhoneDataLayerAppHelper(context.applicationContext, registry)
  }

  /**
   * The connected watches, re-read whenever the set with the watch app installed changes. Empty on
   * a phone without the Wearable API (no Wear OS companion), so callers just show nothing.
   */
  val watches: Flow<List<PairedWatch>> =
    appHelper.connectedAndInstalledNodes
      .map { installed ->
        val legacy = runCatching {
          registry.capabilityClient
            .getCapability(WearCapabilityProbe.CAPABILITY, CapabilityClient.FILTER_ALL)
            .await()
            .nodes
        }
          .getOrDefault(emptySet())
        val installedIds = (installed + legacy).mapTo(HashSet()) { it.id }
        registry.nodeClient.connectedNodes.await().map { node ->
          PairedWatch(node.id, node.displayName, appInstalled = node.id in installedIds)
        }
      }
      .catch { error ->
        Log.i(TAG, "watch install status unavailable: ${error.message}")
        emit(emptyList())
      }
      .distinctUntilChanged()

  /**
   * Opens the app's Play Store listing on [nodeId]. Must be called while the phone app is in the
   * foreground (Horologist enforces it). Returns whether the watch accepted the request; the user
   * finishes the install on the watch.
   */
  suspend fun installOnWatch(nodeId: String): Boolean =
    runCatching { appHelper.installOnNode(nodeId) }
      .onFailure { Log.w(TAG, "install on $nodeId failed", it) }
      .getOrNull() == AppHelperResultCode.APP_HELPER_RESULT_SUCCESS

  private companion object {
    const val TAG = "WearAppInstall"
  }
}
