@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.annotation.RestrictTo
import androidx.compose.foundation.layout.Box
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.runBlocking

/**
 * Cached counterpart to upstream
 * `androidx.compose.remote.tooling.preview.RemotePreview`.
 *
 * The difference that matters: this version keys the captured `.rc`
 * bytes on a caller-supplied [cacheKey] and stores hits in
 * [LocalCardDocumentCache]. Two calls with the same key skip the
 * encode entirely and play the cached bytes — even across Activity
 * recreation, because the cache is process-scoped.
 *
 * Cache-key contract: include everything that bakes into the document
 * (the card YAML, theme colours / dark flag, profile), and nothing
 * that the host can update by named binding (entity state, attributes,
 * `is_on`). Snapshot data is read by [content] on capture but must
 * NOT be part of the key — `LiveValues.state` / `.attribute` /
 * `.isOn` (in rc-components) are exactly the seam through which a
 * running player gets fresh data without re-encoding.
 *
 * Sizing follows upstream `RemotePreview`: the captured document
 * carries its natural size in the header, and the player measures via
 * `RemoteComposePlayerFlags.shouldPlayerWrapContentSize` (on by
 * default), so callers size the slot via [modifier] and don't have to
 * thread pixels through.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
@Composable
fun CachedCardPreview(
    cacheKey: Any,
    profile: Profile = RcPlatformProfiles.ANDROIDX,
    modifier: Modifier = Modifier,
    content: @RemoteComposable @Composable () -> Unit,
) {
    val context = LocalContext.current
    val cache = LocalCardDocumentCache.current

    val cardDocument =
        remember(cacheKey) {
            cache.get(cacheKey)
                ?: runBlocking {
                        val captured =
                            captureSingleRemoteDocument(
                                context = context,
                                profile = profile,
                                content = content,
                            )
                        CardDocument(bytes = captured.bytes, widthPx = 0, heightPx = 0)
                    }
                    .also { cache.put(cacheKey, it) }
        }

    val coreDocument = remember(cardDocument) { cardDocument.decode() }

    Box(modifier = modifier) {
        RemoteDocumentPlayer(
            document = coreDocument,
            documentWidth = cardDocument.widthPx,
            documentHeight = cardDocument.heightPx,
        )
    }
}
