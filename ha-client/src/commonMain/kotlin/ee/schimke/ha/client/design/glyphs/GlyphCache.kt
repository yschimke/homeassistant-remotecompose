package ee.schimke.ha.client.design.glyphs

import ee.schimke.ha.client.platformHttpClient
import ee.schimke.ha.design.glyphs.GlyphPack
import ee.schimke.ha.design.glyphs.GlyphPackCodec
import ee.schimke.ha.design.glyphs.GlyphRequest
import ee.schimke.ha.design.glyphs.GlyphStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Glyphs for the faces a player can't draw itself, fetched from the design server a character range
 * at a time (`GET <base>/fonts/glyphs`, `docs/architecture/glyph-range-fonts.md`) and kept in a
 * [GlyphStore]: a face at a size is one stored pack that grows as text needs more of it, so every
 * card that sets text in it draws from the same glyphs, across designs and restarts.
 *
 * [bits] is the depth glyphs travel and are stored at; 2 looks all but identical to 8 at a wall
 * panel's sizes. [token] is the bearer for [baseUrl], as for its designs; blank sends none.
 */
class GlyphCache(
  private val baseUrl: String,
  private val store: GlyphStore,
  private val client: HttpClient = platformHttpClient(),
  private val bits: Int = 8,
  private val token: suspend () -> String = { "" },
) {
  init {
    require(bits in GlyphPackCodec.BITS) { "bits must be one of ${GlyphPackCodec.BITS}" }
  }

  /** The stored pack for [request]'s face and size, whatever it covers, or null. */
  suspend fun stored(request: GlyphRequest): GlyphPack? =
    store.read(request.key)?.let(GlyphPackCodec::decode)?.takeIf { it.key == request.key }

  /**
   * A pack with [request]'s characters: the stored one when it has them all, else the stored one
   * with the missing ones fetched and merged in (and stored). Offline, or for characters the face
   * can't draw, it has what it has: check [GlyphPack.chars]. Null when nothing is stored or
   * fetched.
   */
  suspend fun glyphs(request: GlyphRequest): GlyphPack? =
    // One read-fetch-merge-write at a time a face and size: two cards asking for different
    // characters at once would each merge into the same stored pack, and the last write win.
    lockFor(request.key).withLock { fill(request) }

  private suspend fun fill(request: GlyphRequest): GlyphPack? {
    val stored = stored(request)
    val missing = request.chars - stored?.chars.orEmpty() - absent[request.key].orEmpty()
    if (missing.isEmpty()) return stored
    val fetched = fetch(request.copy(chars = missing)) ?: return stored
    // A character the face doesn't have comes back without a glyph: not asked again this run.
    absent.getOrPut(request.key) { mutableSetOf() } += missing - fetched.chars
    val merged = stored?.merge(fetched) ?: fetched
    store.write(request.key, GlyphPackCodec.encode(merged, bits))
    return merged
  }

  private val absent = mutableMapOf<String, MutableSet<String>>()

  private val locksLock = Mutex()
  private val locks = mutableMapOf<String, Mutex>()

  private suspend fun lockFor(key: String): Mutex = locksLock.withLock {
    locks.getOrPut(key) { Mutex() }
  }

  private suspend fun fetch(request: GlyphRequest): GlyphPack? {
    val url = buildString {
      append(baseUrl.trimEnd('/'))
      append("/fonts/glyphs?family=").append(request.family.encodeURLParameter())
      append("&size=").append(request.size)
      append("&density=").append(request.density)
      append("&weight=").append(request.weight)
      append("&italic=").append(if (request.italic) 1 else 0)
      append("&bits=").append(bits)
      append("&chars=").append(request.chars.sorted().joinToString("").encodeURLParameter())
    }
    val bearer = token()
    val response =
      runCatching {
        client.get(url) {
          if (bearer.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $bearer")
        }
      }
        .getOrNull()
        ?.takeIf { it.status.isSuccess() } ?: return null
    return GlyphPackCodec.decode(response.bodyAsBytes())?.takeIf { it.key == request.key }
  }
}
