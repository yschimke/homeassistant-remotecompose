@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RcProfiles
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.creation.CreationDisplayInfo
import androidx.compose.remote.creation.RemoteComposeWriterAndroid
import androidx.compose.remote.creation.platform.AndroidxRcPlatformServices
import androidx.compose.remote.creation.profile.Profile

/**
 * Same as [androidXExperimental], but with `Header.FEATURE_PAINT_MEASURE = 0` baked into the
 * document. The default in alpha08 is `1` ("direct measure in paint, instead of wrap behavior"),
 * which makes [androidx.compose.remote.player.view.platform.RemoteComposeView.onMeasure] report the
 * document's declared `mWidth`/`mHeight` to its parent regardless of the root layout's intrinsic
 * size. With the feature off, the player runs a real measure pass against the parent's
 * `maxWidth`/`maxHeight` and reports `mRootLayoutComponent.getWidth()/getHeight()` — i.e. the
 * document's natural content size — back up the View hierarchy.
 *
 * Visually this is a no-op when the host wraps the player in `Modifier.size(...)` /
 * `Modifier.fillMaxSize()` (the `EXACTLY` MeasureSpec ignores the intrinsic). It becomes useful
 * when the host wants to flow cards by their own content size — e.g. a flexbox-style dashboard
 * layout, or a wrap-content card slot — at which point the document needs to be authored with the
 * wrap-friendly measure path. We bake the bit in now so future host changes don't have to
 * re-encode every card.
 *
 * Multiple `HEADER` ops are legal in the wire format; [CoreDocument.initFromBuffer] walks all
 * operations in order and assigns `mHeader = header` for each, so the **last** Header wins. The
 * super constructor writes the four standard tags (DOC_WIDTH/HEIGHT/CONTENT_DESCRIPTION/PROFILES);
 * we append a second Header carrying `FEATURE_PAINT_MEASURE = 0` from the subclass `init`.
 */
private class WrapModeWriter(
    creationDisplayInfo: CreationDisplayInfo,
    profile: Profile,
    callback: Any?,
) : RemoteComposeWriterAndroid(creationDisplayInfo, null, profile, callback) {
    init {
        // mBuffer is protected on RemoteComposeWriter — accessible from this subclass.
        mBuffer.addHeader(shortArrayOf(Header.FEATURE_PAINT_MEASURE), arrayOf<Any>(0))
    }
}

/**
 * AndroidX experimental profile (FlowLayout / grid-card ops enabled) emitting documents with
 * `FEATURE_PAINT_MEASURE = 0`. Drop-in for [androidXExperimental] at any capture site that wants
 * the document to participate in wrap-content measurement.
 */
val androidXExperimentalWrap: Profile =
    Profile(
        CoreDocument.DOCUMENT_API_LEVEL,
        RcProfiles.PROFILE_ANDROIDX or RcProfiles.PROFILE_EXPERIMENTAL,
        AndroidxRcPlatformServices(),
    ) { creationDisplayInfo, profile, callback ->
        WrapModeWriter(creationDisplayInfo, profile, callback)
    }
