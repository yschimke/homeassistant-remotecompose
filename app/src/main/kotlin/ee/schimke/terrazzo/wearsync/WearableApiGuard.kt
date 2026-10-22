package ee.schimke.terrazzo.wearsync

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log

/**
 * Whether this phone can use the Wearable Data Layer API without crashing the app.
 *
 * Connecting any Wearable client (Data, Message, Node or Capability) runs Play services Wearable's
 * signature check on the China Wear OS companion, [CN_COMPANION]. play-services-wearable 20.0.1
 * reads that package's `PackageInfo.signatures` without a null check, so a phone where the package
 * is visible but reports no signatures throws a NullPointerException on Play services' own
 * `GoogleApiHandler` thread, where nothing in the app can catch it (Crashlytics:
 * `com.google.android.gms.wearable.internal.zzkp.zzc`, "Attempt to get length of null array"). On
 * such a phone the Wearable API is never touched, and the watch features stay hidden as they do on
 * a phone without Wear OS.
 */
object WearableApiGuard {
  /** Package whose signature the Wearable client verifies on connect. */
  const val CN_COMPANION = "com.google.android.wearable.app.cn"

  @Volatile private var usable: Boolean? = null

  /** Checked once per process: a package change mid-process is not worth re-reading for. */
  fun isUsable(context: Context): Boolean =
    usable
      ?: companionUsable(context.packageManager).also {
        usable = it
        if (!it) Log.w(TAG, "$CN_COMPANION can't be verified; Wearable API disabled")
      }

  /**
   * [info] is what Play services sees for [CN_COMPANION]: absent is fine (the check returns early),
   * present without signatures is the crash.
   */
  internal fun isUsable(info: PackageInfo?): Boolean = info == null || info.signatures != null

  /**
   * Play services' own lookup catches only [PackageManager.NameNotFoundException], so any other
   * failure here would throw on its thread too: unusable, not absent.
   */
  @Suppress("DEPRECATION") // GET_SIGNATURES is the flag Play services itself queries with.
  private fun companionUsable(pm: PackageManager): Boolean =
    try {
      isUsable(pm.getPackageInfo(CN_COMPANION, PackageManager.GET_SIGNATURES))
    } catch (_: PackageManager.NameNotFoundException) {
      true
    } catch (e: RuntimeException) {
      Log.w(TAG, "can't read $CN_COMPANION", e)
      false
    }

  private const val TAG = "WearableApiGuard"
}
