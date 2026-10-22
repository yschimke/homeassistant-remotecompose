package ee.schimke.terrazzo.wearsync

import android.content.pm.PackageInfo
import android.content.pm.Signature
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mirrors play-services-wearable's connect-time signature check on the China Wear OS companion,
 * which dereferences `PackageInfo.signatures` without a null check (Crashlytics: `zzkp.zzc`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearableApiGuardTest {
  @Test
  fun absentCompanionIsUsable() {
    assertTrue(WearableApiGuard.isUsable(null as PackageInfo?))
  }

  @Test
  fun signedCompanionIsUsable() {
    val info = PackageInfo().apply { signatures = arrayOf(Signature(byteArrayOf(1, 2, 3))) }
    assertTrue(WearableApiGuard.isUsable(info))
  }

  @Test
  fun companionWithoutSignaturesIsNotUsable() {
    val info = PackageInfo().apply { signatures = null }
    assertFalse(WearableApiGuard.isUsable(info))
  }
}
