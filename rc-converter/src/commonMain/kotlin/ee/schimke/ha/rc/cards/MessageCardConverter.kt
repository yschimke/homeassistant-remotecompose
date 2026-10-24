@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.*
import ee.schimke.ha.rc.*
import ee.schimke.ha.rc.components.*
import kotlinx.serialization.json.jsonPrimitive

/** Message form uses re-encode for choice lists and initial values; host drafts survive it. */
class MessageCardConverter : CardConverter {
  override val cardType: String = MESSAGE_CARD_TYPE

  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    messageInputs(card, snapshot).toString()

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int =
    40 + messageInputs(card, snapshot).sumOf { if (it.multiline) 140 else 116 }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    RemoteHaInputForm(
      card.raw["title"]?.jsonPrimitive?.content ?: "Send to a room",
      LocalCardSizeMode.current == CardSizeMode.Fixed,
      modifier,
    ) {
      val fields = messageInputs(card, snapshot)
      fields.forEachIndexed { index, field ->
        RemoteHaInput(
          field.key,
          field.label,
          field.value,
          field.options,
          field.multiline,
          submit =
            if (index == fields.lastIndex)
              MESSAGE_SUBMIT +
                ":" +
                encodeHaAction(
                  parseHaAction(
                    card.raw["tap_action"] as? kotlinx.serialization.json.JsonObject,
                    null,
                  )
                )
            else "",
          chrome = field.chrome,
        )
      }
    }
  }
}
