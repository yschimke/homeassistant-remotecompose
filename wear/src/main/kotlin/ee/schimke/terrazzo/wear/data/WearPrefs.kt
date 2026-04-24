package ee.schimke.terrazzo.wear.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ee.schimke.ha.rc.components.ThemeStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Wear-local theme picker storage. The phone app keeps its own
 * [ThemeStyle] in `terrazzo-core`'s `PreferencesStore`, but that module
 * targets API 35 and the Wear floor is API 30, so the Wear stub keeps
 * a tiny private DataStore. A future change can sync the two via
 * Wear Data Layer (DataClient).
 */
class WearPrefs(private val context: Context) {

    val themeStyle: Flow<ThemeStyle> =
        context.store.data.map { prefs ->
            prefs[THEME_KEY]?.let { runCatching { ThemeStyle.valueOf(it) }.getOrNull() }
                ?: ThemeStyle.TerrazzoHome
        }

    suspend fun setThemeStyle(style: ThemeStyle) {
        context.store.edit { it[THEME_KEY] = style.name }
    }

    companion object {
        private val Context.store by preferencesDataStore(name = "terrazzo_wear_prefs")
        private val THEME_KEY = stringPreferencesKey("theme_style")
    }
}
