@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.core.RcProfiles
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.RemoteComposeWriterAndroid
import androidx.compose.remote.creation.platform.AndroidxRcPlatformServices
import androidx.compose.remote.creation.profile.Profile

/**
 * Profile used when capturing the `.rc` document that ships to the launcher as a pinned widget (or
 * to the notification system as a `CustomBigContentView`). Both surfaces hand the bytes to
 * `RemoteViews.DrawInstructions`, which is interpreted by the platform's RemoteCompose runtime —
 * that runtime supports a stricter op set than the embedded AndroidX player, so capturing with the
 * widgets profile up-front keeps the bytes within the launcher's vocabulary.
 *
 * Shape mirrors `RcPlatformProfiles.WIDGETS_V7` (document API level 7,
 * `RcProfiles.PROFILE_WIDGETS`) but ORs in `PROFILE_EXPERIMENTAL` so experimental widget ops are
 * admitted at capture time.
 *
 * **`FEATURE_PAINT_MEASURE = 0` (no paint-measure for widgets).** The alpha default is `1`
 * ("measure during paint instead of wrap behaviour"), which makes
 * [androidx.compose.remote.player.view.platform.RemoteComposeView.onMeasure] skip the document's
 * measure pass and only re-measures content lazily during `dispatchDraw`. A widget's slot size is
 * *externally forced* by the launcher (the cell grid), and the host re-uses one player view across
 * resizes — the embedded long-press preview's `WrapAdaptiveRemoteDocumentPlayer`, and the
 * launcher's own `RemoteViews.DrawInstructions` view. With paint-measure on, that re-used view
 * paints blank the moment it's re-measured at a new size (its layout was cached at the first draw
 * and never rebuilt), which is the "widget goes blank once resized" bug. With the feature off the
 * player runs a real measure pass against the (EXACTLY) slot on every size change, so `fillMaxSize`
 * re-fills and content reflows to the new cell — exactly what we want when the size is dictated
 * from outside.
 *
 * Baking the bit requires the writer's `HTag` varargs constructor (the flag lands in the same
 * single `HEADER` op that carries DOC_WIDTH / DOC_HEIGHT / DOC_PROFILES), which means dropping the
 * `writerCallback` — same tradeoff [androidXExperimentalWrap] already takes: it only tracks
 * PendingIntent-style side effects baked by the writer, and these cards issue none (widget taps go
 * through named actions dispatched at playback, not writer-baked intents).
 */
val widgetsProfile: Profile =
  Profile(
    7,
    RcProfiles.PROFILE_WIDGETS or RcProfiles.PROFILE_EXPERIMENTAL,
    AndroidxRcPlatformServices(),
  ) { creationDisplayInfo, profile, _ ->
    RemoteComposeWriterAndroid(
      profile,
      RemoteComposeWriter.hTag(Header.DOC_WIDTH, creationDisplayInfo.width),
      RemoteComposeWriter.hTag(Header.DOC_HEIGHT, creationDisplayInfo.height),
      RemoteComposeWriter.hTag(Header.DOC_CONTENT_DESCRIPTION, ""),
      RemoteComposeWriter.hTag(Header.DOC_PROFILES, profile.operationsProfiles),
      RemoteComposeWriter.hTag(Header.FEATURE_PAINT_MEASURE, 0),
    )
  }
