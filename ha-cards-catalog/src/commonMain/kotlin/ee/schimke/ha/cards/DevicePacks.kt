package ee.schimke.ha.cards

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The device packs' designs (card-designs/ui-builder/packs, docs/design/device-packs.md), bundled
 * at build time and drawn by [CardDesignDocument]: what `:previews` renders, so each pack design
 * gets a preview and its changes show in a pull request's preview diff, and what the card design
 * library publishes ([designs]).
 */
object DevicePacks {
  /** Every pack design's `<pack>/<file>` path (`garage-door/alert.uid`), in order. */
  val paths: List<String>
    get() = BundledDevicePacks.paths.filter { it.endsWith(".uid") }

  /** The pack design at [path], ready to draw, or null when there is none. */
  fun design(path: String): CardDesignDocument? =
    BundledDevicePacks.file(path)?.let(CardDesignDocument::parse)

  /**
   * One design of a pack, as its `pack.json` lists it: which pack and kind it is, its file and
   * title, and who it is for (the pack's audience: [domain], and [deviceClass] when the pack names
   * one).
   */
  data class PackDesign(
    val pack: String,
    val designId: String,
    val kind: String,
    val variant: String,
    val surface: String,
    val file: String,
    val title: String,
    val contract: String,
    val domain: String,
    val deviceClass: String?,
  ) {
    /** `<pack>/<file>`, as [paths] lists it. */
    val path: String
      get() = "$pack/$file"
  }

  /** Every pack's designs, pack by pack, in each `pack.json`'s order. */
  val designs: List<PackDesign> by lazy {
    BundledDevicePacks.paths
      .filter { it.endsWith("/pack.json") }
      .flatMap { path ->
        val manifest = json.parseToJsonElement(BundledDevicePacks.file(path)!!).jsonObject
        val pack = path.substringBefore('/')
        val audience = manifest["audience"]?.jsonObject ?: JsonObject(emptyMap())
        fun JsonObject.text(name: String) = this[name]?.jsonPrimitive?.contentOrNull
        manifest["designs"]!!.jsonArray.map {
          val d = it.jsonObject
          PackDesign(
            pack = pack,
            designId = d.text("designId")!!,
            kind = d.text("kind")!!,
            variant = d.text("variant")!!,
            surface = d.text("surface")!!,
            file = d.text("file")!!,
            title = d.text("title")!!,
            contract = manifest.text("contract")!!,
            domain = audience.text("entity.domain")!!,
            deviceClass = audience.text("entity.device_class"),
          )
        }
      }
  }

  /**
   * The `.uid` text of [design] with each example entity it was made with (`cover.garage_door`)
   * named as the contract's slots are (`cover.s0`, then `cover.s1`), by domain in the order they
   * appear: the names a host pushes a card's entities under, so the design draws the card's own.
   */
  fun forSlots(design: PackDesign): String = forSlots(BundledDevicePacks.file(design.path)!!)

  /** [document] (`.uid` text) with its example entities named as slots: see [forSlots]. */
  fun forSlots(document: String): String {
    val parsed = json.parseToJsonElement(document).jsonObject
    val nodes = parsed["nodes"]?.jsonObject ?: return document
    val slots = linkedMapOf<String, String>()
    val perDomain = mutableMapOf<String, Int>()
    for (node in nodes.values) {
      val entity = node.jsonObject.entityId() ?: continue
      if (entity.isBlank() || '.' !in entity || entity in slots) continue
      val domain = entity.substringBefore('.')
      val index = perDomain.getOrElse(domain) { 0 }
      perDomain[domain] = index + 1
      slots[entity] = "$domain.s$index"
    }
    if (slots.isEmpty()) return document
    val renamed = nodes.mapValues { (_, node) ->
      val n = node.jsonObject
      val slot = n.entityId()?.let(slots::get) ?: return@mapValues node
      val properties = n.getValue("properties").jsonObject
      val value =
        JsonObject(properties.getValue(ENTITY_ID).jsonObject + ("value" to JsonPrimitive(slot)))
      JsonObject(n + ("properties" to JsonObject(properties + (ENTITY_ID to value))))
    }
    return json.encodeToString(
      JsonObject.serializer(),
      JsonObject(parsed + ("nodes" to JsonObject(renamed))),
    )
  }

  private fun JsonObject.entityId(): String? {
    val p = this["properties"]?.jsonObject?.get(ENTITY_ID)?.jsonObject ?: return null
    if (p["type"]?.jsonPrimitive?.contentOrNull != "string") return null
    return p["value"]?.jsonPrimitive?.contentOrNull
  }

  private const val ENTITY_ID = "entityId"
  private val json = Json { ignoreUnknownKeys = true }
}
