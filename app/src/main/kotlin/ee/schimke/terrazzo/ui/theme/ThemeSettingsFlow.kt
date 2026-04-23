package ee.schimke.terrazzo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import ee.schimke.terrazzo.core.prefs.AppearancePrefs
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import kotlinx.coroutines.flow.map

/**
 * Bridges the string-based [AppearancePrefs] DataStore flow into the
 * typed [ThemeSettings] the UI consumes. Unknown / missing strings
 * fall back to the enum's default — the store keeps data around through
 * future option churn.
 */
@Composable
fun rememberThemeSettings(store: PreferencesStore): State<ThemeSettings> {
    val flow = remember(store) { store.appearance.map { it.toThemeSettings() } }
    return flow.collectAsState(initial = ThemeSettings())
}

private fun AppearancePrefs.toThemeSettings(): ThemeSettings = ThemeSettings(
    colorSource = colorSource.toEnumOr(ColorSource.MaterialDynamic),
    typography = typography.toEnumOr(TypographyChoice.MaterialDefault),
    darkMode = darkMode.toEnumOr(DarkMode.FollowSystem),
)

private inline fun <reified E : Enum<E>> String.toEnumOr(default: E): E =
    if (isEmpty()) default else runCatching { enumValueOf<E>(this) }.getOrDefault(default)
