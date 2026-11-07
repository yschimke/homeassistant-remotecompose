package ee.schimke.terrazzo.widget

import android.content.Context
import android.os.SystemClock
import ee.schimke.ha.client.design.DesignFilters
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.rc.LauncherFontFilter
import ee.schimke.ha.rc.LiveText
import ee.schimke.ha.rc.OnDeviceWidgetFonts
import ee.schimke.ha.rc.WidgetBitmapFonts
import ee.schimke.terrazzo.core.prefs.WidgetCardFontsPref
import ee.schimke.terrazzo.dashboard.CardDesignSync
import ee.schimke.terrazzo.terrazzoGraph
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The launcher widgets' card fonts, drawn from bitmap fonts: the launcher plays a widget with the
 * system's faces only, so a widget that names a card font (Rugged Digital's G-SPOT headings) goes
 * through a bitmap-font filter, where [WidgetCardFontsPref] says:
 * - [WidgetCardFontsPref.Server]: the design server's filter for `launcher-widgets`
 *   ([DesignFilters.bitmapFonts]), sent only a design template, captured from placeholder input,
 *   never a widget's own contents (docs/privacy.md);
 * - [WidgetCardFontsPref.OnDevice]: drawn on the phone ([OnDeviceWidgetFonts]), for every widget,
 *   offline, from the card fonts the app has cached;
 * - [WidgetCardFontsPref.Off]: none, and the widget keeps its theme's own face.
 *
 * With no filter, or one that can't answer (offline, an older server, a font not cached), the
 * widget is captured without the card fonts, as before.
 *
 * Filtered documents are cached by their source's hash, locale and density: a template is the same
 * bytes on every refresh. A request that fails holds the filter off for [RETRY_AFTER_MS], so a
 * widget refresh never waits on a server that isn't answering.
 */
internal object LauncherWidgetFonts {
  /** The filter [WidgetCardFontsPref] asks for, or null when there is none to use now. */
  fun filterFor(context: Context): LauncherFontFilter? {
    val prefs = context.terrazzoGraph().preferencesStore
    return when (runBlocking { prefs.widgetCardFonts.first() }) {
      WidgetCardFontsPref.Off -> null
      WidgetCardFontsPref.OnDevice -> onDevice(context)
      WidgetCardFontsPref.Server -> server(context)
    }
  }

  /** Drawn on the phone, from the card fonts cached for the design server, if any are. */
  private fun onDevice(context: Context): LauncherFontFilter? {
    if (!WidgetCardFonts.installed(context)) return null
    val live = LiveText(locale = Locale.getDefault().toLanguageTag())
    val drawn = OnDeviceWidgetFonts.launcherFilter(context, live)
    return object : LauncherFontFilter by drawn {
      override fun filter(bytes: ByteArray, densityDpi: Int): ByteArray? {
        if (!DesignFilters.namesAny(bytes, WidgetBitmapFonts.FAMILIES)) return null
        val key = "device:${Sha256.hex(bytes)}:${live.locale}:$densityDpi"
        return filtered[key] ?: drawn.filter(bytes, densityDpi)?.also { filtered[key] = it }
      }
    }
  }

  /** The design server's filter, or null when there is none to ask now. */
  private fun server(context: Context): LauncherFontFilter? {
    if (SystemClock.elapsedRealtime() < retryAt) return null
    val prefs = context.terrazzoGraph().preferencesStore
    val choice = runBlocking { CardDesignSync.choice(context, prefs).first() }
    if (choice.serverUrl.isBlank()) return null
    val token = CardDesignSync.tokenFor(context, choice)
    // Live text in the language the phone shows: its letters, or text where they won't fit.
    val locale = Locale.getDefault().toLanguageTag()
    return LauncherFontFilter { bytes, densityDpi ->
      if (!DesignFilters.namesAny(bytes, WidgetBitmapFonts.FAMILIES)) return@LauncherFontFilter null
      val density = (densityDpi / 160f).coerceIn(1f, 4f)
      val key = "${Sha256.hex(bytes)}:$locale:$density"
      filtered[key]
        ?: if (SystemClock.elapsedRealtime() < retryAt) null
        else
          runBlocking {
            withTimeoutOrNull(TIMEOUT_MS) {
              DesignFilters.bitmapFonts(
                choice.serverUrl,
                bytes,
                DesignFilters.LAUNCHER_WIDGETS,
                density,
                token,
                locale = locale,
              )
            }
          }
            ?.also { filtered[key] = it }
            ?: null.also { retryAt = SystemClock.elapsedRealtime() + RETRY_AFTER_MS }
    }
  }

  @Volatile private var retryAt = 0L

  private val filtered: MutableMap<String, ByteArray> =
    Collections.synchronizedMap(
      object : LinkedHashMap<String, ByteArray>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) =
          size > CACHE_SIZE
      },
    )

  private const val CACHE_SIZE = 64
  private const val TIMEOUT_MS = 10_000L
  private const val RETRY_AFTER_MS = 15 * 60_000L
}
