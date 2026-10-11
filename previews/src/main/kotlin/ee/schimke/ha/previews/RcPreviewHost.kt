@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ee.schimke.ha.rc.HaRcComposePlayer
import ee.schimke.ha.rc.WrapAdaptiveRemoteDocumentPlayer
import kotlinx.coroutines.runBlocking

/**
 * The preview module's Remote Compose host: capture [content] under [profile] and play it with the
 * Compose Multiplatform `RcComposePlayer` ([HaRcComposePlayer]).
 *
 * Replaces upstream's `RemoteContentPreview`, which plays through the AndroidX View player. That
 * player runs animations on its own clock, so a still captured from it lands at an arbitrary point
 * of any running tween and the PNG flickers between runs
 * (`docs/bugs/rc-alpha18-player-divergence.md`). The Compose player animates on the composition's
 * frame clock, which the render harness controls.
 *
 * Shaped like `RemoteContentPreview` so call sites only swap the host: same capture call, same
 * `remember(profile, content)` memoisation, the document sized to the container the caller pinned.
 * [onDocument] sees the captured bytes once per composition (the `.rc` sidecar hook, see
 * `RcDocumentCapture.kt`).
 *
 * Best-effort: a capture the profile rejects yields an empty box rather than failing the render. A
 * document rc-player-compose reports it can't fully render falls back to the AndroidX player.
 */
@Composable
internal fun RcContentPreview(
  profile: Profile,
  modifier: Modifier = Modifier,
  bitmapLoader: BitmapLoader = BitmapLoader.UNSUPPORTED,
  onDocument: ((ByteArray) -> Unit)? = null,
  content: @Composable @RemoteComposable () -> Unit,
) {
  val context = LocalContext.current
  val bytes: ByteArray? =
    remember(profile, content) {
      runCatching {
        runBlocking {
          captureSingleRemoteDocument(context = context, profile = profile, content = content).bytes
        }
      }
        .getOrNull()
    }
  if (bytes != null && onDocument != null) {
    androidx.compose.runtime.SideEffect { onDocument(bytes) }
  }
  Box(modifier) {
    if (bytes != null) {
      HaRcComposePlayer(
        documentBytes = bytes,
        modifier = Modifier.fillMaxSize(),
        bitmapLoader = bitmapLoader,
        fallback = {
          WrapAdaptiveRemoteDocumentPlayer(
            documentBytes = bytes,
            modifier = Modifier.fillMaxSize(),
            bitmapLoader = bitmapLoader,
          )
        },
      )
    }
  }
}
