package ee.schimke.ha.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Resolved Lovelace dashboard config, matching the payload returned by the HA WebSocket command
 * `lovelace/config`.
 *
 * The schema is intentionally loose: HA treats any unknown field on a card as opaque, and the
 * frontend's TypeScript types carry an `[key: string]: any` escape hatch. We surface `extra:
 * JsonObject` on every config node so converters can read card-specific fields without us having to
 * model them up front.
 */
@Serializable data class Dashboard(val title: String? = null, val views: List<View> = emptyList())

@Serializable
data class View(
  val title: String? = null,
  val path: String? = null,
  val icon: String? = null,
  val theme: String? = null,
  val type: String? = null,
  /**
   * Maximum number of columns the view uses on a wide enough host. Only meaningful for `type:
   * sections`; renderers honour it on tablet+ widths and collapse to a single column on mobile.
   */
  val maxColumns: Int? = null,
  val sections: List<Section> = emptyList(),
  val cards: List<CardConfig> = emptyList(),
  val badges: List<JsonObject> = emptyList(),
)

@Serializable
data class Section(
  val type: String? = null,
  val title: String? = null,
  val cards: List<CardConfig> = emptyList(),
  /**
   * Sections-view per-section overrides. `column_span` lets a section occupy more than one grid
   * column on a wide layout (HA defaults to 1 if absent). `row_span` similarly stretches
   * vertically.
   */
  val columnSpan: Int? = null,
  val rowSpan: Int? = null,
)

/**
 * A Lovelace card config. Only `type` is guaranteed — everything else lives in [raw] for per-card
 * converters to interpret.
 *
 * Lovelace serialises card fields directly on the card object (no `raw` wrapper), so a custom
 * serializer captures the entire JSON object as [raw] rather than letting kotlinx-serialization
 * look for a literal `raw` field that the wire format never emits.
 */
@Serializable(with = CardConfigSerializer::class)
data class CardConfig(val type: String, val raw: JsonObject = JsonObject(emptyMap()))

object CardConfigSerializer : KSerializer<CardConfig> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): CardConfig {
    val input = decoder as? JsonDecoder ?: error("CardConfig is JSON-only")
    val obj = input.decodeJsonElement().jsonObject
    val type = obj["type"]?.jsonPrimitive?.content ?: error("Card config missing required 'type'")
    return CardConfig(type = type, raw = obj)
  }

  override fun serialize(encoder: Encoder, value: CardConfig) {
    val output = encoder as? JsonEncoder ?: error("CardConfig is JSON-only")
    val obj =
      if (value.raw["type"] == JsonPrimitive(value.type)) value.raw
      else JsonObject(value.raw + ("type" to JsonPrimitive(value.type)))
    output.encodeJsonElement(obj)
  }
}
