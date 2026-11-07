package ee.schimke.ha.rc

import android.content.Context
import ee.schimke.composeai.rcplayer.compose.RcComposeTextRasterizer
import ee.schimke.composeai.rcplayer.edit.RcDisplay
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits

/**
 * Card fonts drawn as bitmap fonts on this device ([WidgetBitmapFonts]), with rc-players' Android
 * rasterizer over the card fonts the app has installed ([HaFontLibrary]): the other half of the
 * design server's `filters/bitmap-fonts`, for a widget whose own contents mustn't leave the device,
 * or a phone with no server to ask. A family the library hasn't got is declined and stays text.
 */
object OnDeviceWidgetFonts {
  /**
   * [bytes] with its card fonts drawn for [host] at [density], or null when it names none or isn't
   * a document.
   */
  fun convert(
    context: Context,
    bytes: ByteArray,
    host: WidgetHost,
    density: Float,
    live: LiveText = LiveText(),
  ): ByteArray? {
    val document = runCatching { RcDocumentCodec.decode(bytes, RcWireLimits()) }.getOrNull()
    if (document == null) return null
    val result =
      WidgetBitmapFonts.apply(
        document,
        host,
        RcComposeTextRasterizer(context.applicationContext, HaFontLibrary.loader()),
        RcDisplay(density),
        live,
      )
    return if (result.texts.isEmpty()) null else RcDocumentCodec.encode(result.document)
  }

  /** A launcher widget's [LauncherFontFilter], which may be handed its own contents. */
  fun launcherFilter(context: Context, live: LiveText = LiveText()): LauncherFontFilter =
    object : LauncherFontFilter {
      override fun filter(bytes: ByteArray, densityDpi: Int): ByteArray? =
        convert(context, bytes, WidgetHost.Launcher, densityDpi / 160f, live)

      override val readsContents: Boolean
        get() = true
    }
}
