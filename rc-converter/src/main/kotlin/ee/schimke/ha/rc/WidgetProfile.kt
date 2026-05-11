@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.core.RcProfiles
import androidx.compose.remote.creation.RemoteComposeWriterAndroid
import androidx.compose.remote.creation.platform.AndroidxRcPlatformServices
import androidx.compose.remote.creation.profile.Profile

/**
 * Profile used when capturing the `.rc` document that ships to the
 * launcher as a pinned widget (or to the notification system as a
 * `CustomBigContentView`). Both surfaces hand the bytes to
 * `RemoteViews.DrawInstructions`, which is interpreted by the
 * platform's RemoteCompose runtime — that runtime supports a stricter
 * op set than the embedded AndroidX player, so capturing with the
 * widgets profile up-front keeps the bytes within the launcher's
 * vocabulary.
 *
 * Shape mirrors `RcPlatformProfiles.WIDGETS_V7` (document API level 7,
 * `RcProfiles.PROFILE_WIDGETS`) but ORs in `PROFILE_EXPERIMENTAL` so
 * experimental widget ops are admitted at capture time.
 */
val widgetsProfile: Profile =
    Profile(
        7,
        RcProfiles.PROFILE_WIDGETS or RcProfiles.PROFILE_EXPERIMENTAL,
        AndroidxRcPlatformServices(),
    ) { creationDisplayInfo, _, profile ->
        RemoteComposeWriterAndroid(creationDisplayInfo, null, profile)
    }
