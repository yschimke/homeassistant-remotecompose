package ee.schimke.terrazzo.core.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Small app-level preferences. Only one flag for now: whether the user
 * opted in to demo mode, which routes the dashboard screens through
 * [ee.schimke.terrazzo.core.session.DemoHaSession] instead of a real
 * HA session.
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

    companion object {
        private val Context.store by preferencesDataStore(name = "terrazzo_prefs")
        private val DEMO_KEY = booleanPreferencesKey("demo_mode")
    }
}
