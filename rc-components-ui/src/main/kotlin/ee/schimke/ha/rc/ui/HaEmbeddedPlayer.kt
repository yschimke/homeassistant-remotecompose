@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.rememberRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity

/**
 * Tier-2 embedding host — RemotePreview, but with a [BitmapLoader]
 * knob.
 *
 * `androidx.compose.remote.tooling.preview.RemotePreview` captures
 * its `content` into a `.rc` document and plays it inline; it's the
 * standard preview / embedding helper. It does **not**, however,
 * accept a `BitmapLoader`, so its `RemoteDocumentPlayer` falls back
 * to the default loader (`AndroidBitmapLoader` →
 * `URL.openStream()`). Any document that uses `RemoteHaImageUrl` /
 * `addNamedBitmapUrl` blows up when played with the default loader
 * in offline contexts (Robolectric `@Preview` renders, widget
 * captures off the network, integration tests).
 *
 * [HaEmbeddedPlayer] is the same capture + play pattern with the
 * loader threaded through to the player, so embedding surfaces have
 * a single knob to wire — pass `CoilBitmapLoader` from
 * `rc-image-coil` in production, `previewCoilBitmapLoader` in
 * previews / tests.
 *
 * The container's measured size becomes the document's intrinsic
 * size — same contract as `RemotePreview`. Use [profile] to pick the
 * RemoteCompose op set (typically `androidXExperimental` /
 * `androidXExperimentalWrap` from `rc-converter`).
 */
@Composable
fun HaEmbeddedPlayer(
    profile: Profile,
    modifier: Modifier = Modifier,
    bitmapLoader: BitmapLoader = BitmapLoader.UNSUPPORTED,
    content: @Composable @RemoteComposable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        val densityDpi = (density.density * 160f).toInt()
        val doc =
            rememberRemoteDocument(
                creationDisplayInfo = RemoteCreationDisplayInfo(widthPx, heightPx, densityDpi),
                profile = profile,
            ) {
                content()
            }
        doc.value?.let { document ->
            RemoteDocumentPlayer(
                document = document,
                documentWidth = widthPx,
                documentHeight = heightPx,
                modifier = Modifier.fillMaxSize(),
                bitmapLoader = bitmapLoader,
            )
        }
    }
}
