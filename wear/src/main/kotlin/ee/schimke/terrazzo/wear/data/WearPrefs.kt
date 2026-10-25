package ee.schimke.terrazzo.wear.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Developer preview capture flag only. Appearance is owned by the phone's WearSettings. */
class WearPrefs(private val context: Context) {

  /**
   * Off-by-default developer flag that enables the slot-preview PNG capture path in
   * [ee.schimke.terrazzo.wear.widget.WearSlotPreviewCapturer]. The path runs end-to-end (virtual
   * display + bitmap encode + write to internal storage) but the resulting PNG isn't yet surfaced
   * to the system widget picker — the Glance Wear alpha API doesn't expose a runtime override for
   * `previewImage`. Flip this on locally to validate the capture mechanics; leave off in shipped
   * builds until a hook lands.
   */
  val previewCaptureEnabled: Flow<Boolean> =
    context.store.data.map { prefs -> prefs[PREVIEW_CAPTURE_KEY] ?: false }

  suspend fun setPreviewCaptureEnabled(enabled: Boolean) {
    context.store.edit { it[PREVIEW_CAPTURE_KEY] = enabled }
  }

  companion object {
    private val Context.store by preferencesDataStore(name = "terrazzo_wear_prefs")
    private val PREVIEW_CAPTURE_KEY = booleanPreferencesKey("preview_capture_enabled")
  }
}
