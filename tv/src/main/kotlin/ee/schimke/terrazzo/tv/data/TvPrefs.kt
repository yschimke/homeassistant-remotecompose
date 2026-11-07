package ee.schimke.terrazzo.tv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * TV-local preferences: the design the kiosk is drawn in plus a demo-mode toggle. Unlike the Wear
 * app, the TV has no phone pairing, so both are local to the TV itself.
 *
 * Defaults to [TvDesign.Kiosk] since wall-mounted dashboards are the TV module's brief.
 */
class TvPrefs(private val context: Context) {

  /**
   * The design ([TvDesign]) the kiosk is drawn in. A TV set up before the design list reads its
   * old palette and card style settings as one ([TvDesign.fromLegacy]).
   */
  val design: Flow<TvDesign> =
    context.store.data.map { prefs ->
      prefs[DESIGN_KEY]?.let(TvDesign::byId)
        ?: TvDesign.fromLegacy(prefs[THEME_KEY], prefs[CARD_STYLE_KEY])
    }

  suspend fun setDesign(design: TvDesign) {
    context.store.edit { it[DESIGN_KEY] = design.id }
  }

  /**
   * Demo-mode toggle. When on, the kiosk preview pulls from `DemoData` instead of the
   * (not-yet-wired) live HA session, so the room sees animated values without a working HA
   * instance. Offered in debug builds only.
   */
  val demoMode: Flow<Boolean> = context.store.data.map { prefs -> prefs[DEMO_KEY] ?: false }

  suspend fun setDemoMode(enabled: Boolean) {
    context.store.edit { it[DEMO_KEY] = enabled }
  }

  companion object {
    private val Context.store by preferencesDataStore(name = "terrazzo_tv_prefs")
    private val DESIGN_KEY = stringPreferencesKey("design")
    /** Legacy (read-only): the palette before [DESIGN_KEY]. */
    private val THEME_KEY = stringPreferencesKey("theme_style")
    /** Legacy (read-only): the card style before [DESIGN_KEY]. */
    private val CARD_STYLE_KEY = stringPreferencesKey("card_style")
    private val DEMO_KEY = booleanPreferencesKey("demo_mode")
  }
}
