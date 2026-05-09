package ee.schimke.terrazzo.wear.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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

    /**
     * Off-by-default developer flag that enables the slot-preview PNG
     * capture path in [ee.schimke.terrazzo.wear.widget.WearSlotPreviewCapturer].
     * The path runs end-to-end (virtual display + bitmap encode +
     * write to internal storage) but the resulting PNG isn't yet
     * surfaced to the system widget picker — the Glance Wear alpha API
     * doesn't expose a runtime override for `previewImage`. Flip this
     * on locally to validate the capture mechanics; leave off in
     * shipped builds until a hook lands.
     */
    val previewCaptureEnabled: Flow<Boolean> =
        context.store.data.map { prefs -> prefs[PREVIEW_CAPTURE_KEY] ?: false }

    suspend fun setPreviewCaptureEnabled(enabled: Boolean) {
        context.store.edit { it[PREVIEW_CAPTURE_KEY] = enabled }
    }

    companion object {
        private val Context.store by preferencesDataStore(name = "terrazzo_wear_prefs")
        private val THEME_KEY = stringPreferencesKey("theme_style")
        private val PREVIEW_CAPTURE_KEY = booleanPreferencesKey("preview_capture_enabled")
    }
}
