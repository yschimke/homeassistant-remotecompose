package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import ee.schimke.composeai.rcplayer.protocol.*
import ee.schimke.composeai.rcplayer.runtime.RcInputDrafts
import ee.schimke.ha.model.*
import kotlinx.serialization.json.jsonPrimitive

/** Authenticated common Android/desktop/Wasm host; the form itself is a portable RC document. */
@Composable
internal fun MessageComposeCard(
  card: CardConfig,
  snapshot: HaSnapshot,
  editable: Boolean = false,
  onAction: (String) -> Unit = {},
) {
  val drafts = remember(card) { RcInputDrafts() }
  var error by remember(card) { mutableStateOf<String?>(null) }
  val fields = remember(card, snapshot) { runCatching { messageInputs(card, snapshot) } }
  val document =
    remember(fields) {
      fields.mapCatching { inputs ->
        RcDocumentCodec.encode(
          rcInputDocument(
            inputs.mapIndexed { index, field ->
              RcInputField(
                field.key,
                field.label,
                field.value,
                field.options,
                field.multiline,
                if (index == inputs.lastIndex) MESSAGE_SUBMIT else "",
                chrome =
                  if (field.chrome == "none")
                    ee.schimke.composeai.rcplayer.protocol.RcInputChrome.NONE
                  else ee.schimke.composeai.rcplayer.protocol.RcInputChrome.DEFAULT,
              )
            }
          )
        )
      }
    }
  Column {
    Text(card.raw["title"]?.jsonPrimitive?.content ?: "Send to a room")
    document.getOrNull()?.let { bytes ->
      RemoteComposeCard(
        bytes,
        Modifier.fillMaxWidth(),
        inputDrafts = drafts,
        inputEditing = editable,
        onInputSubmit = { action, values ->
          if (editable && action == MESSAGE_SUBMIT) {
            runCatching { messageAction(card, snapshot, values).toString() }
              .onSuccess {
                error = null
                onAction(it)
              }
              .onFailure { error = it.message }
          }
        },
      )
    } ?: Text(document.exceptionOrNull()?.message ?: "Message card unavailable")
    error?.let { Text(it) }
  }
}
