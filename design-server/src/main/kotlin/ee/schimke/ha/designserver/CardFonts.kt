package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.CardFontManifest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The fonts card designs name, served beside the designs ([CardFontManifest]): the repository's
 * `card-fonts/`, packaged into the classpath at `fonts/v1/`. The manifest is served with each
 * face's SHA-256 filled in, so a client verifies the bytes it caches; the faces themselves change
 * only with a deployment.
 */
internal class CardFonts(read: (String) -> ByteArray?) {
  /** Each face's bytes, by file name. */
  private val files: Map<String, ByteArray>

  /** The manifest as served, and its ETag. */
  val manifest: ByteArray
  val etag: String

  init {
    val source =
      read("$ROOT/fonts.json")?.decodeToString()?.let(CardFontManifest::decode)
        ?: CardFontManifest()
    val bytes = mutableMapOf<String, ByteArray>()
    val families =
      source.families.map { family ->
        family.copy(
          fonts =
            family.fonts.mapNotNull { face ->
              if (!CardFontManifest.FILE_NAME.matches(face.file)) return@mapNotNull null
              val data = read("$ROOT/${face.file}") ?: return@mapNotNull null
              bytes[face.file] = data
              face.copy(sha256 = Sha256.hex(data))
            },
        )
      }
    files = bytes
    manifest =
      CardFontManifest(families.filter { it.fonts.isNotEmpty() }).encode().encodeToByteArray()
    etag = "\"${Sha256.hex(manifest)}\""
  }

  fun file(name: String): ByteArray? = files[name]

  companion object {
    private const val ROOT = "fonts/v1"

    /** The fonts packaged into this server's classpath. */
    fun fromClasspath(): CardFonts = CardFonts { path ->
      CardFonts::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
    }
  }
}

/**
 * `<base>/fonts/fonts.json` and `<base>/fonts/<file>`: the same fonts for `main` and every channel,
 * as a design names a font the same way whichever library serves it.
 */
internal fun Route.cardFontRoutes(base: String, fonts: CardFonts) {
  get("$base/fonts/{name}") {
    val name = call.parameters["name"].orEmpty()
    call.allowAnyOrigin()
    if (name == "fonts.json") {
      call.response.header(HttpHeaders.ETag, fonts.etag)
      // Revalidated with the designs' manifest; a 304 costs nothing.
      call.response.header(HttpHeaders.CacheControl, "no-cache")
      if (call.request.header(HttpHeaders.IfNoneMatch)?.let { fonts.etag in it } == true) {
        call.respondBytes(ByteArray(0), status = HttpStatusCode.NotModified)
      } else {
        call.respondBytes(fonts.manifest, ContentType.Application.Json)
      }
      return@get
    }
    val bytes = fonts.file(name) ?: return@get call.notFound()
    call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
    call.respondBytes(
      bytes,
      if (name.endsWith(".otf")) ContentType("font", "otf") else ContentType("font", "ttf"),
    )
  }
}
