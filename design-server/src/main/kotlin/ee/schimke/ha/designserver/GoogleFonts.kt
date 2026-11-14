package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.Sha256
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Faces from Google Fonts, for a `google:` family no card font names ([GlyphRanges]): asked of the
 * public CSS API (`fonts.googleapis.com/css2`, no key), which lists each face's file on
 * `fonts.gstatic.com`; a client that isn't a browser is given whole TrueType files. Faces are kept
 * in memory and, with [cacheDir], on disk by family, weight and slant, so a face is fetched once a
 * server; a family Google doesn't have is remembered for [missingTtlMs].
 *
 * The catalogue's faces are under the SIL Open Font License or Apache 2.0, both of which let this
 * server draw them and serve the rasters.
 */
class GoogleFonts
internal constructor(
  private val http: HttpClient,
  private val cacheDir: File? = null,
  private val cssBase: String = CSS_BASE,
  private val now: () -> Long = System::currentTimeMillis,
  private val missingTtlMs: Long = 60 * 60 * 1000,
) {
  class Face(val family: String, val weight: Int, val italic: Boolean, val bytes: ByteArray) {
    val sha256: String = Sha256.hex(bytes)
  }

  private val faces = mutableMapOf<String, Face>()
  private val missing = mutableMapOf<String, Long>()
  private val lock = Mutex()

  /**
   * [family]'s face nearest [weight] and [italic] (the face asked for, else the family's default),
   * or null if Google doesn't have the family or can't be reached. [family] is its name as Google
   * spells it, case and all ("Lobster Two"), without the `google:` prefix.
   */
  suspend fun face(family: String, weight: Int, italic: Boolean): Face? {
    if (!NAME.matches(family)) return null
    val key = "${family.lowercase()} w$weight${if (italic) " italic" else ""}"
    lock.withLock {
      faces[key]?.let {
        return it
      }
      missing[family.lowercase()]?.let { if (now() - it < missingTtlMs) return null }
    }
    val face = fromDisk(key, family, weight, italic) ?: fetch(family, weight, italic)
    lock.withLock { if (face == null) missing[family.lowercase()] = now() else faces[key] = face }
    if (face != null) toDisk(key, face)
    return face
  }

  private suspend fun fetch(family: String, weight: Int, italic: Boolean): Face? {
    val name = family.replace(' ', '+')
    // The face asked for; Google refuses an axis value the family doesn't have, so then its
    // default face (regular, upright).
    val css =
      css("$cssBase?family=$name:ital,wght@${if (italic) 1 else 0},$weight")
        ?: css("$cssBase?family=$name")
        ?: return null
    val face =
      FACE.findAll(css)
        .map { it.groupValues[1] }
        .firstNotNullOfOrNull { block ->
          val url = SRC.find(block)?.groupValues?.get(1) ?: return@firstNotNullOfOrNull null
          val faceWeight = WEIGHT.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 400
          val faceItalic = STYLE.find(block)?.groupValues?.get(1) == "italic"
          Triple(url, faceWeight, faceItalic)
        } ?: return null
    val (url, faceWeight, faceItalic) = face
    // Only Google's own font host: the CSS names the file, and nothing else is fetched for it.
    if (runCatching { Url(url).host }.getOrNull() != FONT_HOST) return null
    val bytes =
      runCatching { http.get(url) }
        .getOrNull()
        ?.takeIf { it.status.isSuccess() }
        ?.bodyAsBytes()
        ?.takeIf { it.size in 1..MAX_FACE_BYTES && isFont(it) } ?: return null
    return Face(family, faceWeight, faceItalic, bytes)
  }

  private suspend fun css(url: String): String? = runCatching {
    // Not a browser: Google answers with whole TrueType files rather than WOFF2 subsets.
    http.get(url) { header(HttpHeaders.UserAgent, USER_AGENT) }
  }
    .getOrNull()
    ?.takeIf { it.status.isSuccess() }
    ?.bodyAsText()
    ?.takeIf { "@font-face" in it }

  private fun file(key: String, suffix: String) = cacheDir?.let {
    File(it, Sha256.hex(key.encodeToByteArray()) + suffix)
  }

  private suspend fun fromDisk(key: String, family: String, weight: Int, italic: Boolean): Face? =
    withContext(Dispatchers.IO) {
      val bytes = file(key, ".ttf")?.takeIf { it.isFile }?.readBytes() ?: return@withContext null
      // Which face the request was answered with: the family's default may stand in.
      val meta = file(key, ".face")?.takeIf { it.isFile }?.readText()?.split(' ')
      val faceWeight = meta?.getOrNull(0)?.toIntOrNull() ?: weight
      val faceItalic = meta?.getOrNull(1)?.let { it == "italic" } ?: italic
      bytes.takeIf { isFont(it) }?.let { Face(family, faceWeight, faceItalic, it) }
    }

  private suspend fun toDisk(key: String, face: Face) {
    withContext(Dispatchers.IO) {
      val ttf = file(key, ".ttf") ?: return@withContext
      runCatching {
        ttf.parentFile.mkdirs()
        val partial = File(ttf.parentFile, ttf.name + ".partial")
        partial.writeBytes(face.bytes)
        partial.renameTo(ttf)
        file(key, ".face")?.writeText("${face.weight} ${if (face.italic) "italic" else "normal"}")
      }
    }
  }

  companion object {
    const val CSS_BASE: String = "https://fonts.googleapis.com/css2"
    const val FONT_HOST: String = "fonts.gstatic.com"
    private const val USER_AGENT = "ha-design-server (glyph ranges)"
    private const val MAX_FACE_BYTES = 40 * 1024 * 1024 // Noto Color Emoji is 25 MB

    /** A family name as Google spells them: letters, digits and spaces. */
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9 ]{0,63}")
    private val FACE = Regex("@font-face\\s*\\{([^}]*)}")
    private val SRC = Regex("src:\\s*url\\(([^)]+)\\)")
    private val WEIGHT = Regex("font-weight:\\s*(\\d+)")
    private val STYLE = Regex("font-style:\\s*(\\w+)")

    /** TrueType or OpenType: what the text stack draws. */
    private fun isFont(bytes: ByteArray): Boolean {
      if (bytes.size < 4) return false
      val tag = bytes.copyOfRange(0, 4)
      return tag.contentEquals(byteArrayOf(0, 1, 0, 0)) ||
        tag.decodeToString() == "OTTO" ||
        tag.decodeToString() == "true"
    }
  }
}
