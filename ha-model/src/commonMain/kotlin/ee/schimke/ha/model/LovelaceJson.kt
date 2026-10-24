package ee.schimke.ha.model

import ee.schimke.ha.design.DeviceInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/**
 * Home Assistant's own JSON, as a dashboard snapshot stores it: a `lovelace/config` payload, the
 * entity states (`entity_states.json`) and the device registry (`devices.json`). The bundled demo
 * dashboards are kept in this shape.
 */
object LovelaceJson {
  private val lenient = Json { ignoreUnknownKeys = true }

  /**
   * A `lovelace/config` payload as a [Dashboard]. The `@Serializable` deserializer doesn't populate
   * [CardConfig.raw] from the flat HA shape, so the tree is walked by hand and each card's whole
   * object kept as `raw` for converters to read.
   */
  fun dashboard(text: String): Dashboard {
    val root = Json.parseToJsonElement(text).jsonObject
    val title = root["title"]?.jsonPrimitive?.content
    val views = (root["views"] as? JsonArray)?.map { view(it.jsonObject) } ?: emptyList()
    return Dashboard(title = title, views = views)
  }

  /** `entity_states.json`: entity id → state and attributes. */
  fun states(text: String): Map<String, EntityState> {
    val root = Json.parseToJsonElement(text).jsonObject
    return root.entries
      .mapNotNull { (id, el) ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val state = obj["state"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val attrs = (obj["attributes"] as? JsonObject) ?: JsonObject(emptyMap())
        id to EntityState(entityId = id, state = state, attributes = attrs)
      }
      .toMap()
  }

  /**
   * `devices.json`: entity id → the device it belongs to, as `HaClient.fetchDevices` reports it
   * from a live Home Assistant. Cards configured by device (the Bambu Lab cards' `printer:`) find
   * their entities through it.
   */
  fun devices(text: String): Map<String, DeviceInfo> =
    lenient.decodeFromString(serializer<Map<String, DeviceInfo>>(), text)

  private fun view(obj: JsonObject): View {
    val title = obj["title"]?.jsonPrimitive?.content
    val type = obj["type"]?.jsonPrimitive?.content
    val maxColumns = obj["max_columns"]?.jsonPrimitive?.content?.toIntOrNull()
    val cards = (obj["cards"] as? JsonArray)?.mapNotNull { it.toCardConfig() } ?: emptyList()
    val sections =
      (obj["sections"] as? JsonArray)?.mapNotNull { el ->
        val s = el as? JsonObject ?: return@mapNotNull null
        Section(
          type = s["type"]?.jsonPrimitive?.content,
          title = s["title"]?.jsonPrimitive?.content,
          cards = (s["cards"] as? JsonArray)?.mapNotNull { e -> e.toCardConfig() } ?: emptyList(),
          columnSpan = s["column_span"]?.jsonPrimitive?.content?.toIntOrNull(),
          rowSpan = s["row_span"]?.jsonPrimitive?.content?.toIntOrNull(),
        )
      } ?: emptyList()
    return View(
      title = title,
      type = type,
      maxColumns = maxColumns,
      denseSectionPlacement =
        obj["dense_section_placement"]?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
      header = ViewHeader.fromJson(obj["header"]),
      cards = cards,
      sections = sections,
    )
  }

  private fun JsonElement.toCardConfig(): CardConfig? {
    val obj = this as? JsonObject ?: return null
    val type = obj["type"]?.jsonPrimitive?.content ?: return null
    return CardConfig(type = type, raw = obj)
  }
}
