package ee.schimke.terrazzo.tv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ee.schimke.ha.rc.components.ThemeStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * TV-local theme picker storage. Mirrors [ee.schimke.terrazzo.wear.data.WearPrefs]
 * — defaults to [ThemeStyle.TerrazzoKiosk] since wall-mounted dashboards
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

    companion object {
        private val Context.store by preferencesDataStore(name = "terrazzo_tv_prefs")
        private val THEME_KEY = stringPreferencesKey("theme_style")
    }
}
