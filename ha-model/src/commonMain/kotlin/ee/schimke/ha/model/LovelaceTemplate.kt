package ee.schimke.ha.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** A reusable view or section. Slots are entity references, never textual substitutions. */
@Serializable
data class LovelaceTemplate(
  val id: String,
  val title: String,
  val kind: Kind,
  val config: JsonObject,
  val slots: List<LovelaceEntitySlot>,
) {
  @Serializable
  enum class Kind {
    View,
    Section,
  }

  /** Placement is all-or-nothing; a stale entity list or a mismatched device class is refused. */
  fun bind(bindings: Map<String, String>, states: Map<String, EntityState>): JsonObject {
    require(slots.map { it.id }.distinct().size == slots.size) { "Duplicate entity slot" }
    require(bindings.keys == slots.map { it.id }.toSet()) { "Bind every entity slot exactly once" }
    for (slot in slots) {
      val state = states[bindings.getValue(slot.id)] ?: error("Entity is no longer available")
      require(slot.accepts(state)) { "Entity does not match ${slot.id}" }
    }
    val used = mutableSetOf<String>()
    fun replace(value: JsonElement, entityReference: Boolean = false): JsonElement =
      when (value) {
        is JsonObject ->
          JsonObject(
            value.mapValues { (key, v) ->
              replace(v, key in setOf("entity", "entity_id", "entities"))
            }
          )
        is JsonArray -> JsonArray(value.map { replace(it, entityReference) })
        is JsonPrimitive -> {
          val slot = slots.firstOrNull {
            entityReference && value.isString && value.content == it.id
          }
          if (slot == null) value
          else {
            used += slot.id
            JsonPrimitive(bindings.getValue(slot.id))
          }
        }
        else -> value
      }
    val result = replace(config).jsonObject
    require(used == bindings.keys) { "Template declares an unused entity slot" }
    return result
  }
}

@Serializable
data class LovelaceEntitySlot(val id: String, val domain: String, val deviceClass: String? = null) {
  fun accepts(entity: EntityState): Boolean =
    entity.entityId.substringBefore('.') == domain &&
      (deviceClass == null ||
        (entity.attributes["device_class"] as? JsonPrimitive)?.content == deviceClass)
}
