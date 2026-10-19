package ee.schimke.terrazzo.shared

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import ee.schimke.composeai.rcplayer.compose.LocalRcLayoutAnimations
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent

/** External images need a host resolver; keep that capability check at the player boundary. */
fun canPlayWithCmp(bytes: ByteArray): Boolean = decodePlayableDocument(bytes).isSuccess

/** A values-free template is safe only when the host can populate every data declaration. */
fun canBindWithCmp(bytes: ByteArray, bindings: Map<String, RcNamedValue>): Boolean =
  decodePlayableDocument(bytes).getOrNull()?.operations?.filterIsInstance<RcNamedVariable>()?.all {
    it.type == RcNamedVariable.COLOR_TYPE ||
      acceptsBinding(it.type, bindings[it.name.removePrefix("USER:")])
  } == true

private fun acceptsBinding(type: Int?, value: RcNamedValue?): Boolean =
  when (value) {
    is RcNamedValue.Text -> type == RcNamedVariable.STRING_TYPE
    is RcNamedValue.Integer -> type == RcNamedVariable.INT_TYPE
    is RcNamedValue.FloatValue -> type == RcNamedVariable.FLOAT_TYPE
    else -> false
  }

private fun decodePlayableDocument(bytes: ByteArray): Result<RcDocument> = runCatching {
  RcDocumentCodec.decode(bytes).also { document ->
    check(
      document
        .composeSupportReport(
          availableCustomComponents = ee.schimke.composeai.rcplayer.protocol.RcInput.configs
        )
        .fullyRenderable
    ) {
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
  animateLayoutChanges: Boolean = true,
  inputDrafts: ee.schimke.composeai.rcplayer.runtime.RcInputDrafts = remember {
    ee.schimke.composeai.rcplayer.runtime.RcInputDrafts()
  },
  inputEditing: Boolean = false,
  onInputSubmit: (String, Map<String, String>) -> Unit = { _, _ -> },
  onEvent: (RcPlayerEvent) -> Unit = {},
) {
  val decoded = remember(bytes) { decodePlayableDocument(bytes) }
  val declared =
    remember(decoded) {
      decoded
        .getOrNull()
        ?.operations
        ?.filterIsInstance<RcNamedVariable>()
        ?.associate { it.name to it.type }
        .orEmpty()
    }
  val accepted = bindings.filter { (name, value) ->
    val type = declared[name] ?: declared["USER:$name"]
    acceptsBinding(type, value)
  }
  // The player's first layout must already see the real labels and readings. Applying them
  // only after composition animates template placeholder widths into the live text on each swap.
  val values =
    remember(decoded) { mutableStateMapOf<String, RcNamedValue>().apply { putAll(accepted) } }
  SideEffect {
    values.keys.toList().filter { it !in accepted }.forEach(values::remove)
    values.putAll(accepted)
  }
  val document = decoded.getOrNull()
  if (document == null) {
    BasicText("This card needs playback capabilities unavailable on this platform.", modifier)
  } else {
    CompositionLocalProvider(LocalRcLayoutAnimations provides animateLayoutChanges) {
      RcComposePlayer(
        document = document,
        modifier = modifier,
        namedValues = values,
        onEvent = onEvent,
        customComponents =
          ee.schimke.composeai.rcplayer.compose.rcInputComponents(
            inputDrafts,
            inputEditing,
            onSubmit = onInputSubmit,
          ),
      )
    }
  }
}
