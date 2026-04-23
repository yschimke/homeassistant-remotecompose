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
 * Small app-level preferences. Holds:
 *
 * - [demoMode]: whether to route dashboard screens through
 *   [ee.schimke.terrazzo.core.session.DemoHaSession] instead of a real
 *   HA session.
 * - [appearance]: three appearance keys (colour source, typography,
 *   dark mode). Stored as plain strings — enum name + `valueOf` — so
 *   adding / removing options doesn't need a schema migration, unknown
 *   values silently fall back to the default.
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

    val appearance: Flow<AppearancePrefs>
        get() = context.store.data.map { prefs ->
            AppearancePrefs(
                colorSource = prefs[COLOR_SOURCE_KEY].orEmpty(),
                typography = prefs[TYPOGRAPHY_KEY].orEmpty(),
                darkMode = prefs[DARK_MODE_KEY].orEmpty(),
            )
        }

    suspend fun setColorSource(value: String) {
        context.store.edit { it[COLOR_SOURCE_KEY] = value }
    }

    suspend fun setTypography(value: String) {
        context.store.edit { it[TYPOGRAPHY_KEY] = value }
    }

    suspend fun setDarkMode(value: String) {
        context.store.edit { it[DARK_MODE_KEY] = value }
    }

    companion object {
        private val Context.store by preferencesDataStore(name = "terrazzo_prefs")
        private val DEMO_KEY = booleanPreferencesKey("demo_mode")
        private val COLOR_SOURCE_KEY = stringPreferencesKey("color_source")
        private val TYPOGRAPHY_KEY = stringPreferencesKey("typography")
        private val DARK_MODE_KEY = stringPreferencesKey("dark_mode")
    }
}

/**
 * Raw appearance values as stored in DataStore. Strings so this module
 * stays free of any dependency on the `ThemeSettings` enums, which live
 * in the `app` module. The UI layer maps between these strings and its
 * enums (missing / unknown → default).
 */
data class AppearancePrefs(
    val colorSource: String,
    val typography: String,
    val darkMode: String,
)
