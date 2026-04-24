package ee.schimke.terrazzo.core.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * User-selectable night-mode preference, layered above the system
 * setting so users can force dark or light regardless of the system
 * value. `Follow` is the default on mobile; Wear and TV ignore it (they
 * are dark-only / light-only respectively).
 */
enum class DarkModePref { Follow, Light, Dark }

/**
 * Top-level theme selection: Material 3 defaults, or one of the
 * curated Terrazzo palettes. The string values here match
 * `ThemeStyle.name` in the rc-components module so the two tables
 * stay in sync without an explicit mapper.
 */
enum class ThemePref { Material3, TerrazzoHome, TerrazzoMushroom, TerrazzoMinimalist, TerrazzoKiosk }

/**
 * Small app-level preferences. Demo-mode flag plus theme choice
 * (Material 3 vs one of four Terrazzo palettes) and dark-mode
 * preference. Reads are `Flow`s so the UI recomposes when the user
 * flips a switch in Settings; widget refresh takes a one-shot read
 * at update time.
 */
@SingleIn(AppScope::class)
@Inject
class PreferencesStore(private val context: Context) {

    val demoMode: Flow<Boolean>
        get() = context.store.data.map { it[DEMO_KEY] ?: false }

    suspend fun demoModeNow(): Boolean = demoMode.first()

    suspend fun setDemoMode(enabled: Boolean) {
        context.store.edit { it[DEMO_KEY] = enabled }
    }

    val themeStyle: Flow<ThemePref>
        get() = context.store.data.map { prefs ->
            prefs[THEME_KEY]?.let { runCatching { ThemePref.valueOf(it) }.getOrNull() }
                ?: ThemePref.TerrazzoHome
        }

    suspend fun themeStyleNow(): ThemePref = themeStyle.first()

    suspend fun setThemeStyle(style: ThemePref) {
        context.store.edit { it[THEME_KEY] = style.name }
    }

    val darkMode: Flow<DarkModePref>
        get() = context.store.data.map { prefs ->
            prefs[DARK_KEY]?.let { runCatching { DarkModePref.valueOf(it) }.getOrNull() }
                ?: DarkModePref.Follow
        }

    suspend fun darkModeNow(): DarkModePref = darkMode.first()

    suspend fun setDarkMode(mode: DarkModePref) {
        context.store.edit { it[DARK_KEY] = mode.name }
    }

    companion object {
        private val Context.store by preferencesDataStore(name = "terrazzo_prefs")
        private val DEMO_KEY = booleanPreferencesKey("demo_mode")
        private val THEME_KEY = stringPreferencesKey("theme_style")
        private val DARK_KEY = stringPreferencesKey("dark_mode")
    }
}
