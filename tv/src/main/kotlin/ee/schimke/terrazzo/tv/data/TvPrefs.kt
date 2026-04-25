package ee.schimke.terrazzo.tv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ee.schimke.ha.rc.components.ThemeStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * TV-local preferences. Mirrors the shape of `WearPrefs` — theme style
 * plus a demo-mode toggle. Unlike the Wear app, the TV has no phone
 * pairing, so demo mode is a local toggle on the TV itself.
 *
 * Defaults to [ThemeStyle.TerrazzoKiosk] since wall-mounted dashboards
 * are the TV module's brief.
 */
class TvPrefs(private val context: Context) {

    val themeStyle: Flow<ThemeStyle> =
        context.store.data.map { prefs ->
            prefs[THEME_KEY]?.let { runCatching { ThemeStyle.valueOf(it) }.getOrNull() }
                ?: ThemeStyle.TerrazzoKiosk
        }

    suspend fun setThemeStyle(style: ThemeStyle) {
        context.store.edit { it[THEME_KEY] = style.name }
    }

    /**
     * Demo-mode toggle. When on, the kiosk preview pulls from
     * `DemoData` instead of the (not-yet-wired) live HA session, so the
     * room sees animated values without a working HA instance.
     */
    val demoMode: Flow<Boolean> =
        context.store.data.map { prefs -> prefs[DEMO_KEY] ?: false }

    suspend fun setDemoMode(enabled: Boolean) {
        context.store.edit { it[DEMO_KEY] = enabled }
    }

    companion object {
        private val Context.store by preferencesDataStore(name = "terrazzo_tv_prefs")
        private val THEME_KEY = stringPreferencesKey("theme_style")
        private val DEMO_KEY = booleanPreferencesKey("demo_mode")
    }
}
