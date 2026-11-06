package ee.schimke.ha.rc

import android.content.Context
import android.graphics.Typeface
import androidx.compose.remote.player.core.platform.FontInstance
import androidx.compose.remote.player.core.platform.TypefaceResolver
import androidx.compose.remote.player.view.RemoteComposePlayer
import java.io.File

/**
 * The fonts a card may name (`RemoteFontFamily.Named`) that ship with the app rather than the
 * device. A card stores only the name; every player that plays our cards resolves it here, and any
 * other name falls through to the player's own resolver.
 *
 * - `digital7` — Digital-7 Mono Italic (Style-7, free for non-commercial use), the segment face of
 *   the GShark watch face that the Rugged Digital kit is drawn from, with its designer.
 * - `gspot` — Russo One (SIL Open Font License 1.1), the nearest open face to the G-SPOT wordmark's
 *   lettering, for Rugged Digital's section headings.
 *
 * Their bytes come from the design server, by way of the font cache ([HaFontLibrary]), shared with
 * the Compose players; a name the cache doesn't hold yet resolves to null.
 */
object HaFonts {
  /** The name a card uses for Digital-7 Mono Italic. */
  const val Digital7 = ee.schimke.ha.rc.components.HaDigital7Font

  /** The name a card uses for Russo One, Rugged Digital's heading face. */
  const val GSpot = ee.schimke.ha.rc.components.HaGSpotFont

  private val loaded = HashMap<String, Typeface?>()

  /** The typeface for [name] from the font cache, or null when it holds none. */
  @Synchronized
  fun typeface(context: Context, name: String): Typeface? {
    val face = HaFontLibrary.bytes(name) ?: return null
    return loaded.getOrPut(face.sha256) {
      runCatching {
        // Typeface reads a file, not bytes: write each face out once, by its hash.
        val file = File(context.cacheDir, "ha-font-${face.sha256}.ttf")
        if (!file.exists() || file.length() != face.bytes.size.toLong()) file.writeBytes(face.bytes)
        Typeface.createFromFile(file)
      }
        .getOrNull()
    }
  }
}

/** [fallback] with the cached card fonts ([HaFonts]) resolved by name first. */
class HaTypefaceResolver(private val context: Context, private val fallback: TypefaceResolver?) :
  TypefaceResolver {
  override fun resolve(
    name: String,
    weight: Int,
    italic: Boolean,
    base: Typeface?,
    flags: Int,
    async: Boolean,
  ): FontInstance =
    HaFonts.typeface(context, name)?.let { Fixed(Typeface.create(it, weight, italic)) }
      ?: fallback?.resolve(name, weight, italic, base, flags, async)
      ?: Fixed(base ?: Typeface.DEFAULT)

  override fun resolve(
    id: Int,
    weight: Int,
    italic: Boolean,
    base: Typeface?,
    flags: Int,
    async: Boolean,
  ): FontInstance =
    fallback?.resolve(id, weight, italic, base, flags, async) ?: Fixed(base ?: Typeface.DEFAULT)

  private class Fixed(private val typeface: Typeface) : FontInstance {
    override fun getTypeface(): Typeface = typeface

    override fun applyVariationSettings(tags: Array<String>, values: FloatArray): Typeface =
      typeface

    override fun setOnLoadedListener(listener: Runnable) = Unit
  }
}

/** Resolve the app's bundled fonts ([HaFonts]) in this player, ahead of its own resolver. */
fun RemoteComposePlayer.installHaFonts() {
  setTypefaceResolver(HaTypefaceResolver(context, typefaceResolver))
}
