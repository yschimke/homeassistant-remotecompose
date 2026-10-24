@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RcProfiles
import androidx.compose.remote.creation.RemoteComposeWriterAndroid
import androidx.compose.remote.creation.platform.AndroidxRcPlatformServices
import androidx.compose.remote.creation.profile.Profile

/**
 * AndroidX profile at the latest document API level *plus* the experimental op bit, which enables
 * operations not yet in the baseline AndroidX set — notably [FlowLayout] (op 240) used by the
 * `hui-glance-card` equivalent.
 *
 * Use this profile in any capture scope that needs `RemoteFlowRow` / `RemoteHaGlance`. Stable
 * profiles (`RcPlatformProfiles.ANDROIDX`) reject those ops with `RuntimeException: Operation N is
 * not supported for this version`.
 */
val androidXExperimental: Profile =
  Profile(
    CoreDocument.DOCUMENT_API_LEVEL,
    RcProfiles.PROFILE_ANDROIDX or RcProfiles.PROFILE_EXPERIMENTAL,
    AndroidxRcPlatformServices(),
  ) { creationDisplayInfo, profile, callback ->
    RemoteComposeWriterAndroid(creationDisplayInfo, null, profile, callback)
  }
