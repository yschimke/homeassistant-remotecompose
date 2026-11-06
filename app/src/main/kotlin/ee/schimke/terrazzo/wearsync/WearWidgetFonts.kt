package ee.schimke.terrazzo.wearsync

import android.content.Context
import ee.schimke.ha.client.design.DesignFilters
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.rc.WidgetBitmapFonts
import ee.schimke.terrazzo.dashboard.CardDesignChoice
import ee.schimke.terrazzo.dashboard.CardDesignSync
import ee.schimke.terrazzo.wearsync.proto.WearSlotDesign
import java.util.Collections
import java.util.Locale

/**
 * The Wear designs the phone sends the watch, with their card fonts drawn from bitmap fonts: the
 * watch's widget host can't load the fonts the app fetches, so a design that names one (Rugged
 * Digital's Digital-7) goes through the design server's bitmap-font filter first
 * ([DesignFilters.bitmapFonts]). A design that names none, or that the server can't filter
 * (offline, an older server), goes as it is and draws its theme's own face, as before.
 */
internal object WearWidgetFonts {
  /** A watch's display, for the glyphs: a Wear widget scales them with the watch's own. */
  private const val WATCH_DENSITY = 2f

  /** Filtered designs by their source's hash: the same design is sent again on every change. */
  private val filtered: MutableMap<String, ByteArray> =
    Collections.synchronizedMap(
      object : LinkedHashMap<String, ByteArray>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) =
          size > CACHE_SIZE
      },
    )

  suspend fun filter(
    context: Context,
    choice: CardDesignChoice?,
    picked: List<Pair<WearSlotDesign, ByteArray>>,
  ): List<Pair<WearSlotDesign, ByteArray>> {
    if (choice == null || choice.serverUrl.isBlank()) return picked
    val token = CardDesignSync.tokenFor(context, choice)
    return picked.map { (design, bytes) ->
      if (!DesignFilters.namesAny(bytes, WidgetBitmapFonts.FAMILIES)) return@map design to bytes
      val withFonts =
        filtered[design.sha256]
          ?: DesignFilters.bitmapFonts(
              choice.serverUrl,
              bytes,
              DesignFilters.WEAR_WIDGETS,
              WATCH_DENSITY,
              token,
              // Live text in the language the phone shows: its letters, or text where they won't
              // fit.
              locale = Locale.getDefault().toLanguageTag(),
            )
            ?.also { filtered[design.sha256] = it }
          ?: return@map design to bytes
      // The watch keeps a design by its hash: the filtered one is another design.
      design.copy(sha256 = Sha256.hex(withFonts)) to withFonts
    }
  }

  private const val CACHE_SIZE = 64
}
