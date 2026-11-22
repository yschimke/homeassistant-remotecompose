package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.validateCardDraft
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.Sha256
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Short-lived, capability-scoped card drafts. No Home Assistant credentials are held here. */
class AgentDesignSessions(private val clock: () -> Long = System::currentTimeMillis) {
  data class Access(val owner: String, val agent: String)

  data class Snapshot(val instruction: String, val document: JsonObject, val revision: Long)

  /**
   * What the agent's own tools ([AgentGuide]) need of its session: the seed's contract, the draft,
   * its attached pictures, the card type it was seeded for and the design key the person's app last
   * previewed it with ([rememberKey]).
   */
  data class Context(
    val instruction: String,
    val original: JsonObject,
    val document: JsonObject,
    val revision: Long,
    val cardType: String?,
    val key: DesignKey?,
    val assets: Map<String, ByteArray>,
  )

  data class Asset(val mediaType: String, val bytes: ByteArray) {
    val sha256: String
      get() = Sha256.hex(bytes)
  }

  private data class Entry(
    val owner: String,
    val agent: String,
    val instruction: String,
    val original: JsonObject,
    val createdAt: Long,
    var expiresAt: Long,
    var document: JsonObject,
    var revision: Long = 0,
    val assets: MutableMap<String, Asset> = mutableMapOf(),
    /** The card type the seed was made for, when the route knew it. */
    val cardType: String? = null,
    /** The design key the person's app last previewed the draft with. */
    var key: DesignKey? = null,
  )

  private val entries = mutableListOf<Entry>()

  /** A session as [AgentSessionFile] keeps it across restarts; images as base64. */
  @Serializable
  data class Saved(
    val owner: String,
    val agent: String,
    val instruction: String,
    val original: JsonObject,
    val createdAt: Long,
    val expiresAt: Long,
    val document: JsonObject,
    val revision: Long,
    val assets: Map<String, SavedAsset> = emptyMap(),
    val cardType: String? = null,
    val key: DesignKey? = null,
  )

  @Serializable data class SavedAsset(val mediaType: String, val base64: String)

  @Synchronized
  fun snapshot(): List<Saved> {
    expire()
    return entries.map { e ->
      Saved(
        e.owner,
        e.agent,
        e.instruction,
        e.original,
        e.createdAt,
        e.expiresAt,
        e.document,
        e.revision,
        e.assets.mapValues { (_, a) ->
          SavedAsset(a.mediaType, Base64.getEncoder().encodeToString(a.bytes))
        },
        e.cardType,
        e.key,
      )
    }
  }

  /** Bring back sessions saved before a restart; expired ones stay gone. */
  @Synchronized
  fun restore(saved: List<Saved>) {
    val now = clock()
    for (s in saved) {
      if (s.expiresAt <= now || entries.any { it.owner == s.owner }) continue
      entries +=
        Entry(
          s.owner,
          s.agent,
          s.instruction,
          s.original,
          s.createdAt,
          s.expiresAt,
          s.document,
          s.revision,
          s.assets
            .mapValues { (_, a) -> Asset(a.mediaType, Base64.getDecoder().decode(a.base64)) }
            .toMutableMap(),
          s.cardType,
          s.key,
        )
    }
  }

  /** Called with the owner capability after a draft, image or revocation changes a session. */
  @Volatile var onChange: (owner: String) -> Unit = {}

  @Synchronized
  fun create(instruction: String, document: JsonObject, cardType: String? = null): Access {
    expire()
    require(instruction.isNotBlank() && instruction.length <= 2000)
    check(entries.size < 128) { "Too many pending design sessions" }
    val owner = UiBuilderConfig.randomToken()
    val agent = UiBuilderConfig.randomToken()
    val now = clock()
    entries +=
      Entry(
        owner,
        agent,
        instruction.trim(),
        document,
        now,
        now + IDLE_MS,
        document,
        cardType = cardType,
      )
    return Access(owner, agent)
  }

  @Synchronized
  fun read(token: String): Snapshot? {
    expire()
    val entry = entries.firstOrNull { it.owner == token || it.agent == token } ?: return null
    return Snapshot(entry.instruction, entry.document, entry.revision)
  }

  /**
   * Keep a session in use alive: an hour after its last activity, never past a day from creation.
   * Returns the seconds left, or null when the session is gone.
   */
  @Synchronized
  fun touch(token: String): Long? {
    expire()
    val entry = entries.firstOrNull { it.owner == token || it.agent == token } ?: return null
    val now = clock()
    entry.expiresAt = minOf(entry.createdAt + MAX_MS, maxOf(entry.expiresAt, now + IDLE_MS))
    return (entry.expiresAt - now) / 1000
  }

  /** The agent's session as its tools see it; null when [token] isn't a live agent's. */
  @Synchronized
  fun context(token: String): Context? {
    expire()
    val entry = entries.firstOrNull { it.agent == token } ?: return null
    return Context(
      entry.instruction,
      entry.original,
      entry.document,
      entry.revision,
      entry.cardType,
      entry.key,
      entry.assets.mapValues { (_, a) -> a.bytes.copyOf() },
    )
  }

  /**
   * Record the design key the person's app previews the draft with: the server never sees the app's
   * own, so the agent's renders use the last one previewed. False when the session is gone.
   */
  @Synchronized
  fun rememberKey(token: String, key: DesignKey): Boolean {
    expire()
    val entry = entries.firstOrNull { it.owner == token || it.agent == token } ?: return false
    entry.key = key
    return true
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
    onChange(entry.owner)
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
    const val IDLE_MS = 60 * 60_000L
    const val MAX_MS = 24 * 60 * 60_000L

    /**
     * Why [document]'s pictures (`document.assets`, by key) can't be drawn from the session's
     * uploaded [assets]: one not attached, or a binding whose `mediaType` or `contentDigest` isn't
     * the upload's (a stale digest after replacing an image under the same key). Empty when every
     * one matches. The submission gate and `check_card_draft` both ask this, so a draft the check
     * passes is one submit accepts.
     */
    fun bindingProblems(document: JsonObject, assets: Map<String, ByteArray>): List<String> =
      (document["assets"] as? JsonObject).orEmpty().mapNotNull { (key, definition) ->
        val bytes =
          assets[key] ?: return@mapNotNull "asset `$key` isn't attached: call put_card_asset first"
        val binding = definition as? JsonObject
        val mediaType = (binding?.get("mediaType") as? JsonPrimitive)?.contentOrNull
        val digest = (binding?.get("contentDigest") as? JsonPrimitive)?.contentOrNull
        if (mediaType == imageType(bytes) && digest == "sha256:${Sha256.hex(bytes)}") null
        else
          "asset `$key` doesn't match its binding: use the mediaType and contentDigest " +
            "put_card_asset returned for it"
      }

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
    bindingProblems(document, entry.assets.mapValues { (_, a) -> a.bytes }).firstOrNull()?.let {
      throw IllegalArgumentException(it)
    }
    entry.document = document
    entry.revision++
    onChange(entry.owner)
    return true
  }

  @Synchronized
  fun revoke(owner: String): Boolean {
    expire()
    val removed = entries.removeIf { it.owner == owner }
    if (removed) onChange(owner)
    return removed
  }

  private fun expire() {
    val now = clock()
    entries.removeIf { it.expiresAt <= now }
  }
}
