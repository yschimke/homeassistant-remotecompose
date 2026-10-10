package ee.schimke.terrazzo.shared

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.rcplayer.compose.rememberRcNamedValues
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent

/** External images need a host resolver; keep that capability check at the player boundary. */
fun canPlayWithCmp(bytes: ByteArray): Boolean = decodePlayableDocument(bytes).isSuccess

private fun decodePlayableDocument(bytes: ByteArray): Result<RcDocument> = runCatching {
  RcDocumentCodec.decode(bytes).also { document ->
    check(document.composeSupportReport().fullyRenderable) {
      "Document needs unsupported playback capabilities"
    }
  }
}

/** Common playback boundary. Live values update without recreating document animations. */
@Composable
fun RemoteComposeCard(
  bytes: ByteArray,
  modifier: Modifier = Modifier,
  bindings: Map<String, RcNamedValue> = emptyMap(),
  onEvent: (RcPlayerEvent) -> Unit = {},
) {
  val decoded = remember(bytes) { decodePlayableDocument(bytes) }
  val values = rememberRcNamedValues()
  SideEffect {
    values.keys.toList().filter { it !in bindings }.forEach(values::remove)
    values.putAll(bindings)
  }
  val document = decoded.getOrNull()
  if (document == null) {
    BasicText("This card needs playback capabilities unavailable on this platform.", modifier)
  } else {
    RcComposePlayer(
      document = document,
      modifier = modifier,
      namedValues = values,
      onEvent = onEvent,
    )
  }
}
