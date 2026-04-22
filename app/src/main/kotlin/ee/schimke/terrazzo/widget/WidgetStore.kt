package ee.schimke.terrazzo.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ee.schimke.ha.model.CardConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Persisted per-widget configuration.
 *
 * Each installed widget keeps enough data to re-render its `.rc`:
 *   - `baseUrl`: which HA instance to pull state from,
 *   - `cardType` + `cardJson`: the Lovelace card config.
 *
 * Capped at 5 installs (product requirement). The cap is enforced on
 * write; the widget provider itself doesn't care.
 */
class WidgetStore(private val context: Context) {

    data class Entry(
        val widgetId: Int,
        val baseUrl: String,
        val card: CardConfig,
    )

    val installed: Flow<List<Entry>>
        get() = context.store.data.map { prefs -> prefs.allEntries() }

    suspend fun count(): Int = installed.first().size

    suspend fun isFull(): Boolean = count() >= MAX_WIDGETS

    suspend fun put(widgetId: Int, baseUrl: String, card: CardConfig) {
        context.store.edit { prefs ->
            prefs[baseUrlKey(widgetId)] = baseUrl
            prefs[cardTypeKey(widgetId)] = card.type
            prefs[cardJsonKey(widgetId)] = json.encodeToString(JsonObject.serializer(), card.raw)
        }
    }

    suspend fun get(widgetId: Int): Entry? = context.store.data.first().readEntry(widgetId)

    suspend fun remove(widgetId: Int) {
        context.store.edit { prefs ->
            prefs.remove(baseUrlKey(widgetId))
            prefs.remove(cardTypeKey(widgetId))
            prefs.remove(cardJsonKey(widgetId))
        }
    }

    private fun Preferences.allEntries(): List<Entry> =
        asMap().keys
            .mapNotNull { k -> widgetIdFromKey(k.name) }
            .distinct()
            .mapNotNull { id -> readEntry(id) }

    private fun Preferences.readEntry(widgetId: Int): Entry? {
        val baseUrl = this[baseUrlKey(widgetId)] ?: return null
        val cardType = this[cardTypeKey(widgetId)] ?: return null
        val cardJson = this[cardJsonKey(widgetId)] ?: return null
        return Entry(
            widgetId = widgetId,
            baseUrl = baseUrl,
            card = CardConfig(
                type = cardType,
                raw = json.parseToJsonElement(cardJson) as JsonObject,
            ),
        )
    }

    companion object {
        const val MAX_WIDGETS = 5

        private val Context.store by preferencesDataStore(name = "terrazzo_widgets")
        private val json = Json { ignoreUnknownKeys = true }

        private fun baseUrlKey(id: Int) = stringPreferencesKey("widget.$id.baseUrl")
        private fun cardTypeKey(id: Int) = stringPreferencesKey("widget.$id.cardType")
        private fun cardJsonKey(id: Int) = stringPreferencesKey("widget.$id.cardJson")

        private val keyPattern = Regex("""widget\.(\d+)\.""")
        private fun widgetIdFromKey(name: String): Int? =
            keyPattern.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}
