@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.creation.profile.RcPlatformProfiles

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
 * Today: alpha010 ships [RcPlatformProfiles.WIDGETS_V7] (document API
 * level 7, `RcProfiles.PROFILE_WIDGETS` bit) — the newer of the two
 * available widget profiles. Cards that lean on AndroidX-only ops
 * (`FlowLayout`, etc.) will fail to capture under this profile —
 * expected. The compatible card subset is what's installable as a
 * widget; the dashboard / preview path keeps using `androidXExperimental`.
 *
 * `WIDGETS_V7` is `@RestrictTo(LIBRARY_GROUP)`; aliasing it here keeps
 * the suppression scoped to a single file.
 */
val widgetsProfile: Profile = RcPlatformProfiles.WIDGETS_V7
