package ee.schimke.ha.rc

import ee.schimke.composeai.rcplayer.compose.RcBundledTypefaceLoader
import ee.schimke.composeai.rcplayer.compose.RcFontFace
import ee.schimke.composeai.rcplayer.compose.RcFontFaces
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import ee.schimke.ha.client.design.CachedFontFace
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.CardFontManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The fonts card designs name (`RemoteFontFamily.Named`) that no device ships: Rugged Digital's
 * `digital7` and `gspot`, say. They are not compiled into the app. The design server serves them
 * beside the designs, and the design sync fetches and caches them (`DesignFonts`). A host
 * [install]s what its cache holds, and every player resolves them by name from here: the Compose
 * players through [loader], the AndroidX View player through [bytes].
 *
 * A build that packages the repository's `card-fonts/` as classpath resources (the previews, tests)
 * starts with them installed; the app doesn't, and starts with none.
 *
 * Until a family is installed, a card naming it draws in its theme's own face. When it arrives,
 * [faces] changes, and a Compose player that follows it ([HaTypefaceLoader]) draws again.
 */
object HaFontLibrary {
  private val _faces = MutableStateFlow(classpathFonts().groupBy { it.family.lowercase() })

  /** The installed faces, by lowercased family name. */
  val faces: StateFlow<Map<String, List<CachedFontFace>>> = _faces.asStateFlow()

  /** Replaces the installed fonts with [faces]: what the device's font cache holds now. */
  fun install(faces: List<CachedFontFace>) {
    _faces.value = faces.groupBy { it.family.lowercase() }
  }

  /** The first face installed for [family], or null when none is. */
  fun bytes(family: String): CachedFontFace? = _faces.value[family.lowercase()]?.firstOrNull()

  /** A Compose player's loader for [faces]. */
  fun loader(faces: Map<String, List<CachedFontFace>> = _faces.value): RcTypefaceLoader {
    last
      ?.takeIf { it.first === faces }
      ?.let {
        return it.second
      }
    val loader =
      if (faces.isEmpty()) RcTypefaceLoader.Empty
      else
        RcBundledTypefaceLoader(
          faces.mapValues { (_, group) ->
            RcFontFaces(
              group.map {
                // The hash is the face's identity: Compose's font cache keys on it.
                RcFontFace("ha-font-${it.sha256}", it.bytes, weight = it.weight, italic = it.italic)
              },
            )
          },
        )
    last = faces to loader
    return loader
  }

  /** The last set of faces' loader, so a player that draws again reuses the parsed fonts. */
  private var last: Pair<Map<String, List<CachedFontFace>>, RcTypefaceLoader>? = null
}

/** The Compose players' loader for the fonts installed now ([HaFontLibrary]). */
val HaTypefaceLoader: RcTypefaceLoader
  get() = HaFontLibrary.loader()

/** A file of the classpath's `card-fonts/`, or null where there is none (or no classpath). */
internal expect fun classpathFontFile(path: String): ByteArray?

/** The faces the classpath's `card-fonts/fonts.json` names, verified as the font cache's are. */
private fun classpathFonts(): List<CachedFontFace> {
  val manifest =
    classpathFontFile("card-fonts/fonts.json")?.decodeToString()?.let {
      runCatching { CardFontManifest.decode(it) }.getOrNull()
    } ?: return emptyList()
  return manifest.faces.mapNotNull { (family, face) ->
    if (!CardFontManifest.FILE_NAME.matches(face.file)) return@mapNotNull null
    val bytes = classpathFontFile("card-fonts/${face.file}") ?: return@mapNotNull null
    CachedFontFace(family, face.weight, face.italic, Sha256.hex(bytes), bytes)
  }
}
