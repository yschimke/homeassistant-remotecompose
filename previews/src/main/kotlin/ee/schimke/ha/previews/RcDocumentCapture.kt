@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.tooling.preview.RemoteContentPreview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ee.schimke.composeai.data.render.IrSidecarChannel
import kotlinx.coroutines.runBlocking

/**
 * Capturing the encoded RemoteCompose document for a preview, so the published catalog carries the
 * **Remote Compose data tier** and not just a baked PNG.
 *
 * The renderer drains [IrSidecarChannel] after each preview composition and writes whatever was
 * offered next to the PNG as `renders/<stem>.rc`; `compose-preview bundle pack` then packs those
 * into the bundle as `ir/<id>.rc`, which is what the public preview server
 * (https://preview.coo.ee/homeassistant-remotecompose/) plays client-side with its RemoteDocument
 * player, and what the design-artifacts PNG↔Remote-Compose parity page diffs against the baked
 * image.
 *
 * Nothing offers into that channel by default. Upstream's in-body `RemoteContentPreview` captures a
 * document internally but never hands the bytes out — only the annotation-driven
 * `@PreviewWrapper(RemotePreviewWrapper::class)` path (which the compose-ai-tools Remote Compose
 * connector substitutes) produces a sidecar, and our previews don't use it: they need
 * `RemoteContentPreview`'s content-sized measure path, where the player's size flows from the
 * surrounding Compose constraints. So the capture is done here instead, alongside the unchanged
 * draw.
 *
 * **What counts as an "appropriate" preview.** Exactly one document per preview. The channel keeps
 * only the last offer for a preview id, so a preview that hosts several documents (the dashboard
 * stack, the theme sheets, the sizing matrices) would publish a sidecar showing only its last slot
 * — worse than publishing none. Those hosts deliberately do not capture; see the call sites of
 * [CapturingRemoteContentPreview] and of `CachedCardPreview`'s `onDocument`.
 */
@Composable
internal fun CapturingRemoteContentPreview(
  profile: Profile,
  modifier: Modifier = Modifier,
  content: @Composable @RemoteComposable () -> Unit,
) {
  CaptureRemoteDocument(profile, content)
  RemoteContentPreview(modifier = modifier, profile = profile, content = content)
}

/**
 * Encode [content] under [profile] once and offer the bytes as this preview's `.rc` sidecar.
 *
 * Draws nothing — [CapturingRemoteContentPreview] pairs it with the real [RemoteContentPreview]
 * draw, so the rendered pixels are byte-for-byte what they were before the capture existed. The
 * cost is a second encode of the same content, which is why it is skipped outside a harness render
 * (the IDE preview pane, a unit test, the app itself): with no current preview id there is nowhere
 * to offer the bytes, and [IrSidecarChannel.offer] would drop them.
 *
 * Best-effort by design — a capture failure must leave the preview rendering. A card whose ops the
 * capture profile rejects loses its `.rc` sidecar and keeps its baked PNG, which is the same
 * degradation the catalog had before.
 */
@Composable
private fun CaptureRemoteDocument(
  profile: Profile,
  content: @Composable @RemoteComposable () -> Unit,
) {
  val context = LocalContext.current
  val previewId = IrSidecarChannel.currentPreviewId()
  // `remember` keyed on the content so the encode happens once per preview composition rather than
  // on every recomposition — the same memoisation upstream's own capture path uses.
  remember(previewId, profile, content) {
    if (previewId != null) {
      runCatching {
          runBlocking {
            captureSingleRemoteDocument(context = context, profile = profile, content = content)
              .bytes
          }
        }
        .onSuccess { offerRemoteDocument(it) }
    }
  }
}

/**
 * Offer already-encoded document [bytes] as the current preview's `.rc` sidecar.
 *
 * For hosts that capture the document themselves rather than through
 * [CapturingRemoteContentPreview] — `CachedCardPreview`'s `onDocument` hook, which hands over the
 * bytes it already cached, so those previews get a sidecar with no second encode at all. A no-op
 * outside a harness render.
 */
internal fun offerRemoteDocument(bytes: ByteArray) {
  IrSidecarChannel.offer(IrSidecarChannel.FORMAT_REMOTECOMPOSE, bytes)
}
