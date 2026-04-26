@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RcProfiles
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.RemoteComposeWriterAndroid
import androidx.compose.remote.creation.platform.AndroidxRcPlatformServices
import androidx.compose.remote.creation.profile.Profile

/**
 * AndroidX experimental profile (FlowLayout / grid-card ops enabled) that emits documents with
 * `Header.FEATURE_PAINT_MEASURE = 0`.
 *
 * The default in alpha08 is `1` ("direct measure in paint, instead of wrap behavior"), which makes
 * [androidx.compose.remote.player.view.platform.RemoteComposeView.onMeasure] report the document's
 * declared `mWidth`/`mHeight` to its parent regardless of the root layout's intrinsic size. With
 * the feature off, the player runs a real measure pass against the parent's `maxWidth`/`maxHeight`
 * and reports `mRootLayoutComponent.getWidth()/getHeight()` — the document's natural content size
 * — back up the View hierarchy.
 *
 * Visually a no-op when the host wraps the player in `Modifier.size(...)` /
 * `Modifier.fillMaxSize()` (the `EXACTLY` MeasureSpec ignores the intrinsic). Becomes useful when
 * the host wants to flow cards by their own content size — flexbox-style dashboard, wrap-content
 * card slot, `RemoteComposePlayerFlags.shouldPlayerWrapContentSize` — at which point the document
 * needs to be authored with the wrap-friendly measure path. Bake the bit in now so future host
 * changes don't have to re-encode every card.
 *
 * The flag has to land in the same single `HEADER` op that carries DOC_WIDTH / DOC_HEIGHT /
 * DOC_CONTENT_DESCRIPTION / DOC_PROFILES, which means we go through the writer's varargs
 * constructor at construction time rather than appending a second `HEADER` op afterwards. The
 * tradeoff: this path doesn't carry the `writerCallback` (no public setter for `mWriterCallback`
 * once the writer is built without it). The callback tracks PendingIntent-style side effects
 * issued from the writer; cards in this project don't issue any, so dropping it is safe today —
 * if/when widget-style PendingIntent actions get added the wrap profile will need to be revisited.
 */
val androidXExperimentalWrap: Profile =
    Profile(
        CoreDocument.DOCUMENT_API_LEVEL,
        RcProfiles.PROFILE_ANDROIDX or RcProfiles.PROFILE_EXPERIMENTAL,
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
