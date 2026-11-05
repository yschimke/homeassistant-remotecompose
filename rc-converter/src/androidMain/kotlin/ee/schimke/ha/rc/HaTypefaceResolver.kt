package ee.schimke.ha.rc

import android.content.Context
import android.graphics.Typeface
import androidx.compose.remote.player.core.platform.FontInstance
import androidx.compose.remote.player.core.platform.TypefaceResolver
import androidx.compose.remote.player.view.RemoteComposePlayer
import ee.schimke.ha.rc.components.HaBundledFonts
import java.io.File

/**
 * The fonts a card may name (`RemoteFontFamily.Named`) that ship with the app rather than the
 * device. A card stores only the name; every player that plays our cards resolves it here, and any
 * other name falls through to the player's own resolver.
 *
 * - `digital7` — Digital-7 Mono Italic (Style-7, free for non-commercial use), the segment face of
 *   the GShark watch face that the Rugged Digital kit is drawn from, with its designer. Its bytes
 *   live in [HaBundledFonts], shared with the Compose players.
 */
object HaFonts {
  /** The name a card uses for Digital-7 Mono Italic. */
  const val Digital7 = ee.schimke.ha.rc.components.HaDigital7Font

  private val loaded = HashMap<String, Typeface?>()

  /** The bundled typeface for [name], or null when the app doesn't ship one. */
  @Synchronized
  fun typeface(context: Context, name: String): Typeface? {
    val bytes = HaBundledFonts.bytes(name) ?: return null
    return loaded.getOrPut(name) {
      runCatching {
        // Typeface reads a file, not bytes: write the font out once per process.
        val file = File(context.cacheDir, "ha-font-$name.ttf")
        if (!file.exists() || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
        Typeface.createFromFile(file)
      }
        .getOrNull()
    }
  }
}

/** [fallback] with the app's bundled fonts ([HaFonts]) resolved by name first. */
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
