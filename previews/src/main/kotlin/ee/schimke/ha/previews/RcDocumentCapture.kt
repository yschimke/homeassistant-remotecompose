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
  val density = context.resources.displayMetrics.density
  remember(previewId, profile, content) {
    if (previewId != null) {
      runCatching {
          runBlocking {
            val bytes =
              captureSingleRemoteDocument(context = context, profile = profile, content = content)
                .bytes
            // Idempotent, never-throws; leaves the bytes alone if it can't stamp.
            stampGenerationDensity(bytes, density)
          }
        }
        .onSuccess(::offerRemoteDocument)
    }
  }
}

// Remote Compose modern-header wire constants (big-endian). The header op is:
//   [op:1][major|MAGIC:4][minor:4][patch:4][propCount:4][ (tag:2)(len:2)(payload:len) ... ]
// where tag = (dataType shl 10) or key, dataType FLOAT = 1, key 7 = DOC_DENSITY_AT_GENERATION.
private const val RC_HEADER_MAGIC = 0x048C0000
private const val RC_PROP_DENSITY_AT_GENERATION = 7
private const val RC_DATATYPE_FLOAT = 1

/**
 * Insert `DOC_DENSITY_AT_GENERATION = density` into a captured RemoteDocument's header.
 *
 * The writer records the document's size in px and its density *behavior*, but not the density
 * *value*, so a player has nothing to scale dp-typed size modifiers by and draws them too small.
 * Every player that reads the property gets it right; one that doesn't is unaffected, since this
 * only appends to the header's property table. The renderer's own PNG is untouched either way — the
 * stamp lands on the sidecar bytes, after the document has been captured.
 *
 * Returns [bytes] unchanged when the density is unusable, the header isn't the modern
 * property-table format, or the property is already present (so it is idempotent). Byte surgery on
 * the header only; mirrors `stampGenerationDensity` in compose-ai-tools' Remote Compose connector,
 * which does this for the `@PreviewWrapper` capture path.
 */
internal fun stampGenerationDensity(bytes: ByteArray, density: Float): ByteArray {
  if (!density.isFinite() || density <= 0f) return bytes
  if (bytes.size < 17) return bytes
  fun beInt(o: Int): Int =
    ((bytes[o].toInt() and 0xFF) shl 24) or
      ((bytes[o + 1].toInt() and 0xFF) shl 16) or
      ((bytes[o + 2].toInt() and 0xFF) shl 8) or
      (bytes[o + 3].toInt() and 0xFF)
  fun beShort(o: Int): Int = ((bytes[o].toInt() and 0xFF) shl 8) or (bytes[o + 1].toInt() and 0xFF)

  if ((beInt(1) and 0xFFFF0000.toInt()) != RC_HEADER_MAGIC) return bytes
  val propCount = beInt(13)
  if (propCount < 0) return bytes
  // Walk the existing property table; bail (leave unchanged) if density is already recorded or the
  // table is malformed.
  var off = 17
  repeat(propCount) {
    if (off + 4 > bytes.size) return bytes
    if ((beShort(off) and 0x3FF) == RC_PROP_DENSITY_AT_GENERATION) return bytes
    off += 4 + beShort(off + 2)
  }

  val tag = (RC_DATATYPE_FLOAT shl 10) or RC_PROP_DENSITY_AT_GENERATION
  val densBits = java.lang.Float.floatToIntBits(density)
  val out = ByteArray(bytes.size + 8)
  System.arraycopy(bytes, 0, out, 0, 17)
  val newCount = propCount + 1
  out[13] = (newCount ushr 24).toByte()
  out[14] = (newCount ushr 16).toByte()
  out[15] = (newCount ushr 8).toByte()
  out[16] = newCount.toByte()
  out[17] = (tag ushr 8).toByte()
  out[18] = tag.toByte()
  out[19] = 0
  out[20] = 4
  out[21] = (densBits ushr 24).toByte()
  out[22] = (densBits ushr 16).toByte()
  out[23] = (densBits ushr 8).toByte()
  out[24] = densBits.toByte()
  System.arraycopy(bytes, 17, out, 25, bytes.size - 17)
  return out
}

/**
 * A sink for hosts that capture the document themselves rather than through
 * [CapturingRemoteContentPreview] — `CachedCardPreview`'s `onDocument` hook, which hands over the
 * bytes it already cached, so those previews get a sidecar with no second encode at all.
 *
 * Stamps the density before offering, exactly as [CaptureRemoteDocument] does. Routing every
 * producer through one sink is what keeps that from drifting: a sidecar that skipped the stamp
 * would scale dp-sized content wrongly in the browser while its neighbours rendered correctly — a
 * difference invisible in the baked PNG and only measurable on the parity page.
 *
 * Returns a stable lambda so `CachedCardPreview` can key its `remember` on it and notify once per
 * document rather than once per recomposition. A no-op outside a harness render.
 */
@Composable
internal fun rememberRemoteDocumentSink(): (ByteArray) -> Unit {
  val density = LocalContext.current.resources.displayMetrics.density
  return remember(density) {
    { bytes -> offerRemoteDocument(stampGenerationDensity(bytes, density)) }
  }
}

/**
 * Offer already-encoded, already-stamped document [bytes] as the current preview's `.rc` sidecar.
 * Prefer [rememberRemoteDocumentSink], which applies the density stamp for you. A no-op outside a
 * harness render.
 */
private fun offerRemoteDocument(bytes: ByteArray) {
  IrSidecarChannel.offer(IrSidecarChannel.FORMAT_REMOTECOMPOSE, bytes)
}
