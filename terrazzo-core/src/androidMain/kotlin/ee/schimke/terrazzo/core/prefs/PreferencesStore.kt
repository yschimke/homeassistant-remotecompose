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

    /**
     * Opt-in to the experimental Compose 1.11 `Grid` API for the
     * dashboard side-by-side section layout. Off by default so the
     * stable `Row` + chunked path keeps shipping; flip from Settings
     * to evaluate visual / behavioural parity against the legacy
     * path. Compact / Medium widths render the same either way (the
     * flag only takes effect in Expanded width with ≥2 sections).
     */
    val experimentalGridLayout: Flow<Boolean>
        get() = context.store.data.map { it[GRID_LAYOUT_KEY] ?: false }

    suspend fun setExperimentalGridLayout(enabled: Boolean) {
        context.store.edit { it[GRID_LAYOUT_KEY] = enabled }
    }

    /**
     * The dashboard the user last opened. Drives auto-launch: on cold
     * start, [ee.schimke.terrazzo.MainActivity] reads this once and
     * seeds the dashboard nav-state, so a single-dashboard or 2-3
     * favourites user lands directly on their content rather than the
     * picker.
     *
     * Encoding:
     *   - **`null`** (key absent) — never opened a dashboard. Show the
     *     picker.
     *   - **[DEFAULT_DASHBOARD_SENTINEL]** — last dashboard was HA's
     *     unnamed default (whose `urlPath` is `null` over the wire).
     *     We can't distinguish "user hasn't picked" from "user picked
     *     the default" if both encoded as `null`, so the default gets
     *     a sentinel string.
     *   - any other string — the named dashboard's `urlPath`.
     */
    val lastViewedDashboard: Flow<String?>
        get() = context.store.data.map { it[LAST_VIEWED_KEY] }

    suspend fun lastViewedDashboardNow(): String? = lastViewedDashboard.first()

    /**
     * Persist the dashboard the user just opened. Pass `null` for HA's
     * default dashboard (which has no `urlPath`); the store encodes it
     * as [DEFAULT_DASHBOARD_SENTINEL] so a subsequent
     * [lastViewedDashboardNow] read can tell "default dashboard" apart
     * from "never opened anything".
     */
    suspend fun setLastViewedDashboard(urlPath: String?) {
        context.store.edit { it[LAST_VIEWED_KEY] = urlPath ?: DEFAULT_DASHBOARD_SENTINEL }
    }

    /**
     * Forget the last dashboard. Called on sign-out / demo toggle so a
     * fresh session doesn't auto-jump into a dashboard from the
     * previous instance — that dashboard might not exist on the new
     * one, and even if it did the user just changed identity and
     * deserves the picker.
     */
    suspend fun clearLastViewedDashboard() {
        context.store.edit { it.remove(LAST_VIEWED_KEY) }
    }

    companion object {
        private val Context.store by preferencesDataStore(name = "terrazzo_prefs")
        private val DEMO_KEY = booleanPreferencesKey("demo_mode")
        private val THEME_KEY = stringPreferencesKey("theme_style")
        private val DARK_KEY = stringPreferencesKey("dark_mode")
        private val LAST_VIEWED_KEY = stringPreferencesKey("last_viewed_dashboard")
        private val GRID_LAYOUT_KEY = booleanPreferencesKey("experimental_grid_layout")

        /** Stored value for HA's default (unnamed) dashboard. */
        const val DEFAULT_DASHBOARD_SENTINEL: String = "__default__"
    }
}
