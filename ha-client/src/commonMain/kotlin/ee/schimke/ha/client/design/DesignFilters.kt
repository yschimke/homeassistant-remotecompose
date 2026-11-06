package ee.schimke.ha.client.design

import ee.schimke.ha.client.platformHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * The design server's document filters (`<designs>/filters/…`), for a document a client hands a
 * host that can't do what the document asks: today the bitmap-font filter, which draws the card
 * fonts the server serves from glyphs embedded in a widget's document.
 */
object DesignFilters {
  /** A Wear widget host: [bitmapFonts]'s `host`. */
  const val WEAR_WIDGETS: String = "wear-widgets"

  /** An Android 16 launcher widget host: [bitmapFonts]'s `host`. */
  const val LAUNCHER_WIDGETS: String = "launcher-widgets"

  /**
   * [document] with its card fonts drawn from bitmap fonts for [host] at [density], from
   * [baseUrl]'s server, or null when it can't be had (offline, an older server): the caller then
   * sends the document as it is, which draws its theme's own face. [token] is the server's bearer,
   * as for its designs.
   */
  suspend fun bitmapFonts(
    baseUrl: String,
    document: ByteArray,
    host: String,
    density: Float,
    client: HttpClient,
    token: suspend () -> String = { "" },
  ): ByteArray? {
    val response =
      runCatching {
        client.post("${baseUrl.trimEnd('/')}/$BITMAP_FONTS_PATH") {
          parameter("host", host)
          parameter("density", density)
          token()
            .takeIf { it.isNotBlank() }
            ?.let { header(HttpHeaders.Authorization, "Bearer $it") }
          contentType(ContentType.Application.OctetStream)
          setBody(document)
        }
      }
        .getOrNull() ?: return null
    if (!response.status.isSuccess()) return null
    return runCatching { response.bodyAsBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }
  }

  /** As [bitmapFonts], with the platform's own HTTP client, closed when done. */
  suspend fun bitmapFonts(
    baseUrl: String,
    document: ByteArray,
    host: String,
    density: Float,
    token: suspend () -> String = { "" },
  ): ByteArray? {
    val client = platformHttpClient()
    return try {
      bitmapFonts(baseUrl, document, host, density, client, token)
    } finally {
      client.close()
    }
  }

  /**
   * Whether [document] names a card font by [families]' names: only then is [bitmapFonts] worth a
   * request. A plain byte search: a family name is stored in the document as its UTF-8 text.
   */
  fun namesAny(document: ByteArray, families: Set<String>): Boolean = families.any { family ->
    contains(document, family.encodeToByteArray())
  }

  private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > haystack.size) return false
    outer@ for (i in 0..haystack.size - needle.size) {
      for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
      return true
    }
    return false
  }

  /** The bitmap-font filter's path under a design server's base URL. */
  const val BITMAP_FONTS_PATH: String = "filters/bitmap-fonts"
}
