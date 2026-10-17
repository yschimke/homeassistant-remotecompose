package ee.schimke.ha.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNull
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

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class View(
  val title: String? = null,
  val path: String? = null,
  val icon: String? = null,
  val theme: String? = null,
  val type: String? = null,
  /**
   * Maximum number of columns the view uses on a wide enough host (HA's `max_columns`, default
   * [SectionsGrid.DEFAULT_MAX_COLUMNS]). Only meaningful for `type: sections`. The camelCase name
   * is still read so dashboards cached before the wire name was mapped keep their value.
   */
  @SerialName("max_columns") @JsonNames("maxColumns") val maxColumns: Int? = null,
  /**
   * HA's `dense_section_placement`: back-fill earlier gaps in the sections grid (`grid-auto-flow:
   * row dense`) instead of always placing the next section after the previous one.
   */
  @SerialName("dense_section_placement") val denseSectionPlacement: Boolean? = null,
  /** The sections view's header (HA 2024.x): a card shown above the sections, plus badges. */
  val header: ViewHeader? = null,
  val sections: List<Section> = emptyList(),
  val cards: List<CardConfig> = emptyList(),
  val badges: List<JsonObject> = emptyList(),
)

/**
 * A sections view's `header` object, mirroring the frontend's `LovelaceViewHeaderConfig`: `{ card?,
 * layout?: "start" | "center" | "responsive", badges_position?: "bottom" | "top", badges_wrap?:
 * "wrap" | "scroll" }`. HA renders [card] (usually a `markdown` card) above the sections, at most
 * [SectionsGrid.HEADER_MAX_WIDTH_DP] wide, centred unless [layout] is `start`.
 *
 * Decoding is lenient: a header card without a `type` reads as no card rather than failing the
 * whole dashboard, and unknown keys are ignored by the callers' `ignoreUnknownKeys` decoders.
 */
@Serializable
data class ViewHeader(
  @Serializable(with = LenientCardConfigSerializer::class) val card: CardConfig? = null,
  val layout: String? = null,
  @SerialName("badges_position") val badgesPosition: String? = null,
  @SerialName("badges_wrap") val badgesWrap: String? = null,
) {
  /** Whether HA centres the header (its default layout is `center`). */
  val centered: Boolean
    get() = layout == null || layout == LAYOUT_CENTER

  companion object {
    const val LAYOUT_CENTER = "center"

    private val lenientJson = Json {
      ignoreUnknownKeys = true
      isLenient = true
    }

    /**
     * Read a raw `header` element, for the hand-written config walkers (demo boards, preview
     * fixtures). Anything that is not a header object reads as null.
     */
    fun fromJson(element: JsonElement?): ViewHeader? {
      if (element !is JsonObject) return null
      return runCatching { lenientJson.decodeFromJsonElement(serializer(), element) }.getOrNull()
    }
  }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Section(
  val type: String? = null,
  val title: String? = null,
  val cards: List<CardConfig> = emptyList(),
  /**
   * Sections-view per-section overrides. `column_span` lets a section occupy more than one grid
   * column on a wide layout (HA defaults to 1 if absent, and clamps it to the columns the view
   * has). `row_span` similarly stretches vertically. See [SectionsGrid].
   */
  @SerialName("column_span") @JsonNames("columnSpan") val columnSpan: Int? = null,
  @SerialName("row_span") @JsonNames("rowSpan") val rowSpan: Int? = null,
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

/**
 * [CardConfigSerializer] for an optional card slot (a view header's `card`): a value that is not a
 * card object with a `type` decodes to null instead of failing the enclosing config.
 */
object LenientCardConfigSerializer : KSerializer<CardConfig?> {
  override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

  override fun deserialize(decoder: Decoder): CardConfig? {
    val input = decoder as? JsonDecoder ?: error("CardConfig is JSON-only")
    val obj = input.decodeJsonElement() as? JsonObject ?: return null
    val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return CardConfig(type = type, raw = obj)
  }

  override fun serialize(encoder: Encoder, value: CardConfig?) {
    val output = encoder as? JsonEncoder ?: error("CardConfig is JSON-only")
    if (value == null) output.encodeJsonElement(JsonNull)
    else CardConfigSerializer.serialize(output, value)
  }
}
