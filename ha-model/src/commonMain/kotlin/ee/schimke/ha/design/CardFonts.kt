package ee.schimke.ha.design

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The fonts card designs name (`RemoteFontFamily.Named`) that no device ships, as the design server
 * serves them beside its designs: `<designs>/fonts/fonts.json`, and each face's file at
 * `<designs>/fonts/<file>`. The shape is the players' font manifest (`RcManifestTypefaceLoader`);
 * the server adds each face's [CardFontFace.sha256], so a client verifies what it caches.
 *
 * A family's [CardFontFamily.name] is the name a card gives it.
 */
@Serializable
data class CardFontManifest(val families: List<CardFontFamily> = emptyList()) {
  /** Every face, with its family's name. */
  val faces: List<Pair<String, CardFontFace>>
    get() = families.flatMap { family -> family.fonts.map { family.name to it } }

  fun encode(): String = json.encodeToString(serializer(), this)

  companion object {
    /** The manifest's path under a design server's base URL. */
    const val PATH = "fonts/fonts.json"

    /** A face file's path under a design server's base URL. */
    fun facePath(file: String): String = "fonts/$file"

    /**
     * A face's file name: a plain name in the manifest's own directory, nothing that escapes it.
     */
    val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.(ttf|otf)")

    fun decode(text: String): CardFontManifest = json.decodeFromString(serializer(), text)

    private val json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      prettyPrint = true
    }
  }
}

/** A family of faces a card names by [name]; [role] says what it is for (display, heading). */
@Serializable
data class CardFontFamily(
  val name: String,
  val role: String = "",
  val fonts: List<CardFontFace> = emptyList(),
)

/**
 * One face of a family: its [file], [weight] and [style] (`normal` or `italic`), and the SHA-256 of
 * its bytes in lowercase hex, which the server fills in.
 */
@Serializable
data class CardFontFace(
  val file: String,
  val weight: Int = 400,
  val style: String = "normal",
  val sha256: String? = null,
) {
  val italic: Boolean
    get() = style.equals("italic", ignoreCase = true)
}
