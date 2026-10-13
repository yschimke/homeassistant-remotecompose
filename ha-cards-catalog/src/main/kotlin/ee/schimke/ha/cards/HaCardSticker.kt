@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ee.schimke.composeai.data.render.IrSidecarChannel
import ee.schimke.ha.rc.HaRcComposePlayer
import ee.schimke.ha.rc.WrapAdaptiveRemoteDocumentPlayer
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import kotlinx.coroutines.runBlocking

/**
 * The frame every `ha-cards` sticker is captured through: a real `RemoteDocument`, recorded under
 * the app's AndroidX + experimental profile inside the Home Assistant light theme, played back with
 * the Compose Multiplatform player `:previews` uses, and offered as the render's `.rc` sidecar so
 * the published catalog ships the document and not only its pixels.
 *
 * The same capture-and-play shape as `:previews`' `CapturingRemoteContentPreview` (see
 * `RcDocumentCapture.kt` there for why not upstream's `RemoteContentPreview`), kept here so this
 * module depends on the shipped components and not on the preview module. Best-effort: a capture
 * the profile rejects draws an empty box rather than failing the render.
 */
@Composable
fun HaCardSticker(content: @Composable @RemoteComposable () -> Unit) {
  val context = LocalContext.current
  val density = context.resources.displayMetrics.density
  val bytes: ByteArray? =
    remember(content) {
      runCatching {
        runBlocking {
          captureSingleRemoteDocument(context = context, profile = androidXExperimental) {
              ProvideHaTheme(HaTheme.Light) {
                RemoteBox(
                  modifier = RemoteModifier.fillMaxSize().padding(8.rdp),
                  contentAlignment = RemoteAlignment.Center,
                  content = content,
                )
              }
            }
            .bytes
        }
      }
        .getOrNull()
    }
  if (bytes != null) {
    SideEffect {
      IrSidecarChannel.offer(
        IrSidecarChannel.FORMAT_REMOTECOMPOSE,
        stampGenerationDensity(bytes, density),
      )
    }
  }
  Box(Modifier.fillMaxSize().background(HaTheme.Light.dashboardBackground)) {
    if (bytes != null) {
      HaRcComposePlayer(
        documentBytes = bytes,
        modifier = Modifier.fillMaxSize(),
        fallback = {
          WrapAdaptiveRemoteDocumentPlayer(documentBytes = bytes, modifier = Modifier.fillMaxSize())
        },
      )
    }
  }
}

// Remote Compose modern-header wire constants (big-endian), as in `:previews`'
// RcDocumentCapture.kt: [op:1][major|MAGIC:4][minor:4][patch:4][propCount:4][(tag:2)(len:2)…].
private const val RC_HEADER_MAGIC = 0x048C0000
private const val RC_PROP_DENSITY_AT_GENERATION = 7
private const val RC_DATATYPE_FLOAT = 1

/**
 * Records `DOC_DENSITY_AT_GENERATION` in a captured document's header so a browser player scales
 * dp-sized content right. A copy of `:previews`' `stampGenerationDensity`; returns [bytes]
 * unchanged when the header is not the modern format or already carries the property.
 */
internal fun stampGenerationDensity(bytes: ByteArray, density: Float): ByteArray {
  if (!density.isFinite() || density <= 0f || bytes.size < 17) return bytes
  fun beInt(o: Int): Int =
    ((bytes[o].toInt() and 0xFF) shl 24) or
      ((bytes[o + 1].toInt() and 0xFF) shl 16) or
      ((bytes[o + 2].toInt() and 0xFF) shl 8) or
      (bytes[o + 3].toInt() and 0xFF)
  fun beShort(o: Int): Int = ((bytes[o].toInt() and 0xFF) shl 8) or (bytes[o + 1].toInt() and 0xFF)

  if ((beInt(1) and 0xFFFF0000.toInt()) != RC_HEADER_MAGIC) return bytes
  val propCount = beInt(13)
  if (propCount < 0) return bytes
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
