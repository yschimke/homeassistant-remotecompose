package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.Text
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.rcInputComponents
import ee.schimke.composeai.rcplayer.protocol.*
import ee.schimke.composeai.rcplayer.runtime.RcInputDrafts
import ee.schimke.ha.model.*
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Foreground Wear app: uses the CMP input protocol; widget capture never calls this host. */
@Composable
internal fun WearMessageCard(
  rawJson: String,
  values: Map<String, EntityValue>,
  onSend: (suspend (String, Map<String, String>) -> Unit)?,
) {
  val drafts = remember(rawJson) { RcInputDrafts() }
  val scope = rememberCoroutineScope()
  var pending by remember(rawJson) { mutableStateOf(false) }
  var status by remember(rawJson) { mutableStateOf<String?>(null) }
  val fields =
    remember(rawJson, values) {
      runCatching {
        val raw = Json.parseToJsonElement(rawJson).jsonObject
        val snapshot =
          HaSnapshot(
            states =
              values.mapValues { (id, value) ->
                EntityState(
                  id,
                  value.state,
                  buildJsonObject {
                    value.attributes["input_options"]?.let {
                      put("options", Json.parseToJsonElement(it))
                    }
                  },
                )
              }
          )
        messageInputs(CardConfig(MESSAGE_CARD_TYPE, raw), snapshot)
      }
    }
  fields.getOrNull()?.let { inputs ->
    val document =
      remember(inputs) {
        rcInputDocument(
          inputs.mapIndexed { index, field ->
            RcInputField(
              field.key,
              field.label,
              field.value,
              field.options,
              field.multiline,
              if (index == inputs.lastIndex) MESSAGE_SUBMIT else "",
            )
          },
          width = 180,
        )
      }
    RcComposePlayer(
      document,
      Modifier.fillMaxWidth(),
      customComponents =
        rcInputComponents(
          drafts,
          editable = onSend != null,
          onSubmit = { action, edited ->
            if (action == MESSAGE_SUBMIT && !pending && onSend != null) {
              pending = true
              status = "Sending…"
              scope.launch {
                try {
                  onSend(rawJson, edited)
                  status = "Message sent"
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                  throw cancelled
                } catch (_: Exception) {
                  status = "Could not confirm sending. Check the phone before retrying."
                } finally {
                  pending = false
                }
              }
            }
          },
        ),
    )
  } ?: Text("Open Home Assistant on the phone to configure this message card")
  status?.let { Text(it) }
}
