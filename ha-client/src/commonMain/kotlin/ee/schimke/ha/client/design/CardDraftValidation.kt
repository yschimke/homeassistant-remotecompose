package ee.schimke.ha.client.design

import ee.schimke.ha.design.HaSharedValues
import kotlinx.serialization.json.*

/**
 * Local draft gate; UI Builder remains authoritative about catalog properties and exportability.
 */
fun validateCardDraft(original: JsonObject, candidate: JsonObject) {
  require(candidate.toString().length <= 128_000) { "Card document is too large." }
  for (field in listOf("schema", "id", "catalogPin", "roots")) {
    require(candidate[field] == original[field]) { "The draft changed the card's $field contract." }
  }
  val originalNodes = original["nodes"]!!.jsonObject
  val nodes = candidate["nodes"]!!.jsonObject
  val originalState = original["stateVariables"]!!.jsonObject
  val candidateState = candidate["stateVariables"]!!.jsonObject
  require(originalState.all { (name, value) -> candidateState[name] == value }) {
    "The draft changed the card's existing bindings."
  }
  for ((name, definition) in candidateState - originalState.keys) {
    val shared = HaSharedValues.valueOf(name)
    val variable = definition as? JsonObject
    require(shared != null && variable != null) { "Unknown shared binding." }
    val value = variable["initialValue"] as? JsonPrimitive
    val valid =
      when (shared.kind) {
        HaSharedValues.Kind.Text ->
          variable["valueType"] == JsonPrimitive("string") && value?.isString == true
        HaSharedValues.Kind.Bool ->
          variable["valueType"] == JsonPrimitive("bool") &&
            value?.isString == false &&
            value.booleanOrNull != null
        HaSharedValues.Kind.Float ->
          variable["valueType"] == JsonPrimitive("float") &&
            value?.isString == false &&
            value.floatOrNull?.isFinite() == true
      }
    require(
      variable.keys.all {
        it in setOf("type", "valueType", "nullable", "initialValue", "persistence")
      } &&
        variable["type"] == JsonPrimitive("value") &&
        valid &&
        variable["nullable"] in setOf(null, JsonPrimitive(false)) &&
        variable["persistence"] in setOf(null, JsonPrimitive("preview"), JsonPrimitive("design")),
    ) {
      "Invalid shared binding."
    }
  }
  require(nodes.size in 1..256)
  val components =
    originalNodes.values.map { it.jsonObject["componentId"] }.toSet() +
      setOf(
        JsonPrimitive("layout/box"),
        JsonPrimitive("layout/column"),
        JsonPrimitive("layout/row"),
        JsonPrimitive("asset/image"),
        JsonPrimitive("ha-cards/remote-text"),
      )
  val assets = (candidate["assets"] as? JsonObject).orEmpty()
  require(assets.size <= 8) { "Too many images." }
  for ((key, value) in assets) {
    val binding = value as? JsonObject ?: error("Invalid image binding.")
    val digest = (binding["contentDigest"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val source = binding["source"] as? JsonObject
    require(
      Regex("[A-Za-z0-9_-]{1,80}").matches(key) &&
        Regex("sha256:[0-9a-f]{64}").matches(digest) &&
        (binding["mediaType"] as? JsonPrimitive)?.contentOrNull in
          setOf("image/png", "image/jpeg", "image/webp") &&
        source?.get("type") == JsonPrimitive("uploaded") &&
        source["storageKey"] == JsonPrimitive(digest),
    ) {
      "Invalid image binding."
    }
  }
  val roots = candidate["roots"]!!.jsonArray.map { it.jsonPrimitive.content }
  val state = candidate["stateVariables"]!!.jsonObject.keys
  fun checkValues(value: JsonElement) {
    when (value) {
      is JsonObject -> {
        if (value["type"] == JsonPrimitive("state"))
          require(value["variable"]?.jsonPrimitive?.content in state) { "Unknown binding." }
        require(
          value["type"]?.jsonPrimitive?.contentOrNull !in setOf("hostAction", "url", "javascript"),
        ) {
          "Agent actions are not allowed."
        }
        value.values.forEach(::checkValues)
      }
      is JsonArray -> value.forEach(::checkValues)
      else -> Unit
    }
  }
  fun references(value: JsonElement): Set<String> =
    when (value) {
      is JsonObject -> {
        val own =
          if (value["type"] == JsonPrimitive("state"))
            setOfNotNull(value["variable"]?.jsonPrimitive?.contentOrNull)
          else emptySet()
        own + value.values.flatMap { references(it) }
      }
      is JsonArray -> value.flatMap { references(it) }.toSet()
      else -> emptySet()
    }
  val previousReads = references(originalNodes)
  val cardBindings = originalState.keys.filterNot { it.startsWith("HA:") }.toSet()
  require(previousReads.isEmpty() || references(nodes).any { it in cardBindings }) {
    "The draft dropped every live card binding."
  }
  val visited = mutableSetOf<String>()
  fun visit(id: String, ancestors: Set<String>) {
    require(id !in ancestors && visited.add(id)) {
      "Card tree contains a cycle or a repeated child."
    }
    val node = nodes[id]?.jsonObject ?: error("Card tree refers to a missing node.")
    require(node["id"] == JsonPrimitive(id) && node["componentId"] in components) {
      "Unsupported card component."
    }
    if (node["componentId"] == JsonPrimitive("asset/image")) {
      val key = ((node["properties"] as? JsonObject)?.get("assetKey") as? JsonObject)
      require(
        key?.get("type") == JsonPrimitive("string") &&
          (key["value"] as? JsonPrimitive)?.contentOrNull in assets,
      ) {
        "Image is not attached."
      }
    }
    require(node["events"] == null || node["events"] == JsonObject(emptyMap())) {
      "Agent actions are not allowed."
    }
    val events = node["eventBindings"]
    val oldEvents = (originalNodes[id] as? JsonObject)?.get("eventBindings")
    require(events == null || events == oldEvents || events == JsonObject(emptyMap())) {
      "Agent actions are not allowed."
    }
    checkValues(node)
    node["slots"]?.jsonObject?.values?.forEach { children ->
      children.jsonArray.forEach { visit(it.jsonPrimitive.content, ancestors + id) }
    }
  }
  roots.forEach { visit(it, emptySet()) }
  require(visited == nodes.keys) { "Card has unreachable nodes." }
  roots.forEach {
    require(
      nodes[it]?.jsonObject?.get("componentId") ==
        originalNodes[it]?.jsonObject?.get("componentId"),
    )
  }
}
