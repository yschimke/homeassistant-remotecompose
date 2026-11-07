package ee.schimke.terrazzo.widget

import android.content.Context
import ee.schimke.ha.rc.HaFontLibrary
import ee.schimke.ha.rc.WidgetBitmapFonts
import ee.schimke.terrazzo.dashboard.CardDesignSync
import ee.schimke.terrazzo.dashboard.CardFonts
import ee.schimke.terrazzo.terrazzoGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** What drawing widgets' card fonts on the phone ([WidgetCardFontsPref.OnDevice]) needs. */
internal object WidgetCardFonts {
  /**
   * Whether any card font is installed for the players ([HaFontLibrary]), loading the design
   * server's cache when none is yet: a widget update can run before the app has.
   */
  fun installed(context: Context): Boolean {
    fun any() = WidgetBitmapFonts.FAMILIES.any { HaFontLibrary.bytes(it) != null }
    if (any()) return true
    val prefs = context.terrazzoGraph().preferencesStore
    runBlocking {
      val choice = CardDesignSync.choice(context, prefs).first()
      CardFonts.load(context, choice.serverUrl)
    }
    return any()
  }
}
