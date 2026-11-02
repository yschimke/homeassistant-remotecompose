package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.client.design.validateCardDraft
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Short-lived, capability-scoped card drafts. No Home Assistant credentials are held here. */
class AgentDesignSessions(private val clock: () -> Long = System::currentTimeMillis) {
  data class Access(val owner: String, val agent: String)

  data class Snapshot(val instruction: String, val document: JsonObject, val revision: Long)

  data class Asset(val mediaType: String, val bytes: ByteArray) {
    val sha256: String
      get() = Sha256.hex(bytes)
  }

  private data class Entry(
    val owner: String,
    val agent: String,
    val instruction: String,
    val original: JsonObject,
    val expiresAt: Long,
    var document: JsonObject,
    var revision: Long = 0,
    val assets: MutableMap<String, Asset> = mutableMapOf(),
  )

  private val entries = mutableListOf<Entry>()

  @Synchronized
  fun create(instruction: String, document: JsonObject): Access {
    expire()
    require(instruction.isNotBlank() && instruction.length <= 2000)
    check(entries.size < 128) { "Too many pending design sessions" }
    val owner = UiBuilderConfig.randomToken()
    val agent = UiBuilderConfig.randomToken()
    entries += Entry(owner, agent, instruction.trim(), document, clock() + 60 * 60_000, document)
    return Access(owner, agent)
  }

  @Synchronized
  fun read(token: String): Snapshot? {
    expire()
    val entry = entries.firstOrNull { it.owner == token || it.agent == token } ?: return null
    return Snapshot(entry.instruction, entry.document, entry.revision)
  }

  @Synchronized
  fun isAgent(token: String): Boolean {
    expire()
    return entries.any { it.agent == token }
  }

  @Synchronized
  fun readAgent(token: String): Snapshot? {
    expire()
    val entry = entries.firstOrNull { it.agent == token } ?: return null
    return Snapshot(entry.instruction, entry.document, entry.revision)
  }

  /** Only the agent may attach images; owner and agent may read their own session's assets. */
  @Synchronized
  fun putAsset(token: String, key: String, bytes: ByteArray): Boolean {
    expire()
    val entry = entries.firstOrNull { it.agent == token } ?: return false
    require(Regex("[A-Za-z0-9_-]{1,80}").matches(key)) { "Invalid asset key" }
    require(bytes.isNotEmpty() && bytes.size <= MAX_ASSET_BYTES) { "Image is too large" }
    val type = imageType(bytes) ?: throw IllegalArgumentException("Unsupported image")
    require(key in entry.assets || entry.assets.size < MAX_ASSETS) { "Too many images" }
    val total = entry.assets.filterKeys { it != key }.values.sumOf { it.bytes.size } + bytes.size
    require(total <= MAX_ASSET_TOTAL_BYTES) { "Images are too large" }
    entry.assets[key] = Asset(type, bytes.copyOf())
    return true
  }

  @Synchronized
  fun asset(token: String, key: String): Asset? {
    expire()
    val entry = entries.firstOrNull { it.owner == token || it.agent == token } ?: return null
    val asset = entry.assets[key] ?: return null
    return Asset(asset.mediaType, asset.bytes.copyOf())
  }

  companion object {
    const val MAX_ASSET_BYTES = 1024 * 1024
    const val MAX_ASSET_TOTAL_BYTES = 2 * 1024 * 1024
    const val MAX_ASSETS = 8

    internal fun imageType(bytes: ByteArray): String? {
      fun prefix(vararg expected: Int) =
        bytes.size >= expected.size &&
          expected.indices.all { (bytes[it].toInt() and 0xff) == expected[it] }
      return when {
        prefix(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
        prefix(0xff, 0xd8, 0xff) -> "image/jpeg"
        bytes.size >= 12 &&
          prefix(0x52, 0x49, 0x46, 0x46) &&
          bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
        else -> null
      }
    }
  }

  /** Accept only an agent's validated update to the revision they read. */
  @Synchronized
  fun submit(token: String, revision: Long, document: JsonObject): Boolean {
    expire()
    val entry = entries.firstOrNull { it.agent == token } ?: return false
    require(revision == entry.revision) { "The design changed; read it again" }
    validateCardDraft(entry.original, document)
    for ((key, definition) in (document["assets"] as? JsonObject).orEmpty()) {
      val uploaded = entry.assets[key] ?: throw IllegalArgumentException("Image not attached")
      val binding = definition.jsonObject
      require(
        binding["mediaType"]?.jsonPrimitive?.content == uploaded.mediaType &&
          binding["contentDigest"]?.jsonPrimitive?.content == "sha256:${uploaded.sha256}"
      ) {
        "Image does not match its binding"
      }
    }
    entry.document = document
    entry.revision++
    return true
  }

  @Synchronized
  fun revoke(owner: String): Boolean {
    expire()
    return entries.removeIf { it.owner == owner }
  }

  private fun expire() {
    val now = clock()
    entries.removeIf { it.expiresAt <= now }
  }
}
