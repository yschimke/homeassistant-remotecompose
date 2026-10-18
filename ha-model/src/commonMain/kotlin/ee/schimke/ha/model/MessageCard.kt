package ee.schimke.ha.model

import kotlinx.serialization.json.*

const val MESSAGE_CARD_TYPE: String = "custom:terrazzo-message"
const val MESSAGE_SUBMIT: String = "ha:submit-inputs:v1"

data class MessageInput(
  val key: String,
  val label: String,
  val value: String,
  val options: List<String>,
  val multiline: Boolean,
  val chrome: String = "default",
)

/** Local configuration and snapshot only: drafts and service data never reach a design server. */
fun messageInputs(card: CardConfig, snapshot: HaSnapshot): List<MessageInput> {
  val fields =
    requireNotNull(card.raw["inputs"] as? JsonArray) { "Message card needs inputs" }
      .map { raw ->
        val field = raw.jsonObject
        val key = requireNotNull(field["key"]?.jsonPrimitive?.content)
        require(Regex("[a-z][a-z0-9_]*").matches(key)) { "Invalid input key" }
        val entity = field["entity"]?.jsonPrimitive?.content?.let(snapshot.states::get)
        val type =
          field["type"]?.jsonPrimitive?.content
            ?: if (
              field["entity"]?.jsonPrimitive?.content?.substringBefore('.') in
                setOf("select", "input_select")
            )
              "choice"
            else "text"
        require(type in setOf("text", "multiline", "choice")) { "Unsupported input type: $type" }
        val options =
          ((field["options"] ?: entity?.attributes?.get("options")) as? JsonArray)
            ?.map { it.jsonPrimitive.content }
            .orEmpty()
        require(type != "choice" || options.isNotEmpty()) { "Choice input needs options" }
        require(
          options.all { it.isNotEmpty() && '\n' !in it } && options.distinct().size == options.size
        )
        val chrome = field["chrome"]?.jsonPrimitive?.content ?: "default"
        require(chrome in setOf("default", "none")) { "Unsupported input chrome: $chrome" }
        MessageInput(
          key,
          field["label"]?.jsonPrimitive?.content ?: key,
          entity?.state ?: field["value"]?.jsonPrimitive?.content.orEmpty(),
          if (type == "choice") options else emptyList(),
          type == "multiline",
          chrome,
        )
      }
  require(fields.isNotEmpty() && fields.map { it.key }.distinct().size == fields.size)
  return fields
}

/**
 * Bind exact {{input.key}} data values, without evaluating templates or changing service/target.
 */
fun messageAction(card: CardConfig, snapshot: HaSnapshot, drafts: Map<String, String>): JsonObject {
  val inputs = messageInputs(card, snapshot)
  val values = inputs.associate { field ->
    val value = drafts[field.key] ?: field.value
    require(value.length <= 4096) { "Input is too long" }
    require(field.options.isEmpty() || value in field.options) { "Choose a valid ${field.label}" }
    field.key to value
  }
  val configured = requireNotNull(card.raw["tap_action"] as? JsonObject)
  require(configured["action"]?.jsonPrimitive?.content in setOf("perform-action", "call-service"))
  val service =
    (configured["perform_action"] ?: configured["service"])!!
      .jsonPrimitive
      .content
      .split('.', limit = 2)
  require(service.size == 2 && service.all { it.isNotBlank() })
  fun bind(element: JsonElement): JsonElement =
    when (element) {
      is JsonObject -> JsonObject(element.mapValues { bind(it.value) })
      is JsonArray -> JsonArray(element.map(::bind))
      is JsonPrimitive ->
        if (
          element.isString &&
            element.content.startsWith("{{input.") &&
            element.content.endsWith("}}")
        ) {
          val key = element.content.removePrefix("{{input.").removeSuffix("}}")
          JsonPrimitive(requireNotNull(values[key]) { "Unknown input $key" })
        } else element
    }
  return buildJsonObject {
    put("type", "ee.schimke.ha.rc.components.HaAction.CallService")
    put("domain", service[0])
    put("service", service[1])
    (configured["target"] as? JsonObject)?.get("entity_id")?.let { put("entityId", it) }
    put(
      "serviceData",
      bind(configured["data"] ?: configured["service_data"] ?: JsonObject(emptyMap())),
    )
  }
}
