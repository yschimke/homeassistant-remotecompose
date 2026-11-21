package ee.schimke.ha.design.glyphs

// Where a device keeps glyph packs, and what it asks for. `ha-client`'s GlyphCache fills a
// GlyphStore from a design server; a pre-filled store works offline.

/** A face at a size, and the characters wanted from it. */
data class GlyphRequest(
  val family: String,
  val size: Float,
  val density: Float = 1f,
  val weight: Int = 400,
  val italic: Boolean = false,
  /** Each character wanted, as its own string (one code point). */
  val chars: Set<String>,
) {
  val key: String
    get() = GlyphPack.key(family, size, density, weight, italic)

  companion object {
    /** [text]'s characters, each once, for [chars]. */
    fun charsOf(text: String): Set<String> = buildSet {
      var index = 0
      while (index < text.length) {
        val high = text[index]
        val length = if (high.isHighSurrogate() && index + 1 < text.length) 2 else 1
        add(text.substring(index, index + length))
        index += length
      }
    }
  }
}

/** Where a device keeps glyph packs, one a face and size, by [GlyphPack.key]. */
interface GlyphStore {
  suspend fun read(key: String): ByteArray?

  suspend fun write(key: String, bytes: ByteArray)
}

/** A [GlyphStore] in memory, for a host that fetches its glyphs on each start, and tests. */
class InMemoryGlyphStore : GlyphStore {
  private val packs = mutableMapOf<String, ByteArray>()

  override suspend fun read(key: String): ByteArray? = packs[key]

  override suspend fun write(key: String, bytes: ByteArray) {
    packs[key] = bytes
  }
}
