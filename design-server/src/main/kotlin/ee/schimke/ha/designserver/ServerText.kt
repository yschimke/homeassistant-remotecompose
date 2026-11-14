package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.compose.RcComposeTextRasterizer
import ee.schimke.composeai.rcplayer.runtime.RcTextRasterizer
import ee.schimke.ha.client.design.CachedFontFace
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.CardFontManifest
import ee.schimke.ha.designs.JvmComposeGraphics
import ee.schimke.ha.rc.HaFontLibrary
import ee.schimke.ha.rc.WidgetBitmapFonts
import kotlinx.coroutines.sync.Mutex

/**
 * The faces this server draws text in and the text stack it draws with, shared by everything that
 * rasterises glyphs ([WidgetFilters], [GlyphRanges]): the card fonts it serves ([CardFonts]) and
 * its own faces (`wall-fonts/`, the wall panel's Roboto, served to no one).
 */
internal class ServerText(fonts: CardFonts) {
  val faces: List<CachedFontFace> = run {
    val manifest = CardFontManifest.decode(fonts.manifest.decodeToString())
    manifest.faces.mapNotNull { (family, face) ->
      val bytes = fonts.file(face.file) ?: return@mapNotNull null
      CachedFontFace(family, face.weight, face.italic, Sha256.hex(bytes), bytes)
    } + wallFaces()
  }

  val rasterizer: RcTextRasterizer = run {
    // Compose's desktop text registers itself when a scene starts, and this server starts none.
    JvmComposeGraphics.ensureRegistered()
    RcComposeTextRasterizer(HaFontLibrary.loader(faces.groupBy { it.family.lowercase() }))
  }

  /** One rasterisation at a time: the text stack draws on one thread. */
  val lock = Mutex()

  /** The faces of [family] (case ignored, a `google:` prefix dropped), or none. */
  fun facesOf(family: String): List<CachedFontFace> {
    val name = family.lowercase().removePrefix("google:")
    return faces.filter { it.family.lowercase() == name }
  }

  private companion object {
    /**
     * The faces [WidgetBitmapFonts.WALL_FAMILY] is drawn in: the classpath's `wall-fonts/`, the
     * panel's own Roboto.
     */
    fun wallFaces(): List<CachedFontFace> =
      listOf("Roboto-Regular.ttf" to 400, "Roboto-Bold.ttf" to 700).mapNotNull { (file, weight) ->
        val bytes =
          ServerText::class.java.classLoader.getResourceAsStream("wall-fonts/$file")?.use {
            it.readBytes()
          } ?: return@mapNotNull null
        CachedFontFace(WidgetBitmapFonts.WALL_FAMILY, weight, false, Sha256.hex(bytes), bytes)
      }
  }
}
