package ee.schimke.terrazzo.core.widget

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.ha.model.CardConfig
import ee.schimke.terrazzo.core.di.AppScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Persisted per-widget configuration.
 *
 * Each installed widget keeps enough data to re-render its `.rc`:
 * - `baseUrl`: which HA instance to pull state from,
 * - `cardType` + `cardJson`: the Lovelace card config,
 * - `live`: whether it keeps up with Home Assistant while the app is closed (live updates, off
 *   until the user turns it on; `docs/architecture/live-widget-updates.md`),
 * - `origin*`: the dashboard the widget was made on and the card pressed there ([Origin]), so a tap
 *   on the widget can open the app on that card. Widgets installed before it was kept have none.
 *
 * Capped at 5 installs (product requirement). The cap is enforced on write; the widget provider
 * itself doesn't care.
 */
@SingleIn(AppScope::class)
@Inject
class WidgetStore(private val context: Context) {

  data class Entry(
    val widgetId: Int,
    val baseUrl: String,
    val card: CardConfig,
    val liveUpdates: Boolean = false,
    val origin: Origin? = null,
  )

  /**
   * Where a widget came from: the dashboard ([dashboardUrlPath], null for Home Assistant's default
   * one) and the card long-pressed on it. That card is the dashboard's own, so the app can find it
   * again; the widget's [Entry.card] may be a part of it, or renamed.
   */
  data class Origin(val dashboardUrlPath: String?, val card: CardConfig)

  val installed: Flow<List<Entry>>
    get() = context.store.data.map { prefs -> prefs.allEntries() }

  suspend fun count(): Int = installed.first().size

  suspend fun isFull(): Boolean = count() >= MAX_WIDGETS

  suspend fun put(
    widgetId: Int,
    baseUrl: String,
    card: CardConfig,
    liveUpdates: Boolean = false,
    origin: Origin? = null,
  ) {
    context.store.edit { prefs ->
      prefs.putEntry(Entry(widgetId, baseUrl, card, liveUpdates, origin))
    }
  }

  suspend fun get(widgetId: Int): Entry? = context.store.data.first().readEntry(widgetId)

  suspend fun remove(widgetId: Int) {
    context.store.edit { prefs -> prefs.removeEntry(widgetId) }
  }

  /**
   * Drops every persisted entry whose widget id is not in [liveIds], returning the number of
   * orphaned rows pruned.
   *
   * [liveIds] is the authoritative set the launcher reports via
   * `AppWidgetManager.getAppWidgetIds()`. Removing a widget from the home screen normally routes
   * through `AppWidgetProvider.onDeleted`, but that callback is easy to miss — the app may not be
   * alive when the user drags the widget off, the launcher's data can be cleared, or a pin offered
   * for a temporary install is dropped. Without this reconcile the rows leak and [count] keeps
   * charging for widgets that no longer exist, eventually wedging the cap at "0 of 5 slots free".
   * Treat the launcher as the source of truth and prune to it.
   */
  suspend fun retainOnly(liveIds: Set<Int>): Int {
    var pruned = 0
    context.store.edit { prefs ->
      val storedIds = prefs.asMap().keys.mapNotNull { widgetIdFromKey(it.name) }.distinct()
      for (id in storedIds) {
        if (id !in liveIds) {
          prefs.removeEntry(id)
          pruned++
        }
      }
    }
    return pruned
  }

  /**
   * Moves each row from `oldIds[i]` to `newIds[i]`: a backup restored onto a new device or launcher
   * gives the same widgets new ids (`AppWidgetProvider.onRestored`), and without this every
   * restored widget would render blank while its old row leaked.
   */
  suspend fun remap(oldIds: IntArray, newIds: IntArray) {
    context.store.edit { prefs ->
      val moved =
        oldIds.zip(newIds).mapNotNull { (old, new) -> prefs.readEntry(old)?.let { new to it } }
      for (old in oldIds) prefs.removeEntry(old)
      for ((new, entry) in moved) prefs.putEntry(entry.copy(widgetId = new))
    }
  }

  private fun Preferences.allEntries(): List<Entry> =
    asMap()
      .keys
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
      card = cardOf(cardType, cardJson),
      liveUpdates = this[liveKey(widgetId)] == true,
      origin = readOrigin(widgetId),
    )
  }

  private fun Preferences.readOrigin(widgetId: Int): Origin? {
    val cardType = this[originCardTypeKey(widgetId)] ?: return null
    val cardJson = this[originCardJsonKey(widgetId)] ?: return null
    return runCatching { Origin(this[originDashboardKey(widgetId)], cardOf(cardType, cardJson)) }
      .getOrNull()
  }

  private fun MutablePreferences.putEntry(entry: Entry) {
    val id = entry.widgetId
    this[baseUrlKey(id)] = entry.baseUrl
    this[cardTypeKey(id)] = entry.card.type
    this[cardJsonKey(id)] = json.encodeToString(JsonObject.serializer(), entry.card.raw)
    // Off is the default, so it is stored only when on.
    if (entry.liveUpdates) this[liveKey(id)] = true else remove(liveKey(id))
    val origin = entry.origin
    if (origin == null) {
      removeOrigin(id)
    } else {
      // The default dashboard has no url path: no key.
      origin.dashboardUrlPath?.let { this[originDashboardKey(id)] = it }
        ?: remove(originDashboardKey(id))
      this[originCardTypeKey(id)] = origin.card.type
      this[originCardJsonKey(id)] = json.encodeToString(JsonObject.serializer(), origin.card.raw)
    }
  }

  private fun MutablePreferences.removeEntry(widgetId: Int) {
    remove(baseUrlKey(widgetId))
    remove(cardTypeKey(widgetId))
    remove(cardJsonKey(widgetId))
    remove(liveKey(widgetId))
    removeOrigin(widgetId)
  }

  private fun MutablePreferences.removeOrigin(widgetId: Int) {
    remove(originDashboardKey(widgetId))
    remove(originCardTypeKey(widgetId))
    remove(originCardJsonKey(widgetId))
  }

  companion object {
    const val MAX_WIDGETS = 5

    private val Context.store by preferencesDataStore(name = "terrazzo_widgets")
    private val json = Json { ignoreUnknownKeys = true }

    private fun baseUrlKey(id: Int) = stringPreferencesKey("widget.$id.baseUrl")

    private fun cardTypeKey(id: Int) = stringPreferencesKey("widget.$id.cardType")

    private fun cardJsonKey(id: Int) = stringPreferencesKey("widget.$id.cardJson")

    private fun liveKey(id: Int) = booleanPreferencesKey("widget.$id.live")

    private fun originDashboardKey(id: Int) = stringPreferencesKey("widget.$id.originDashboard")

    private fun originCardTypeKey(id: Int) = stringPreferencesKey("widget.$id.originCardType")

    private fun originCardJsonKey(id: Int) = stringPreferencesKey("widget.$id.originCardJson")

    private fun cardOf(type: String, cardJson: String) =
      CardConfig(type = type, raw = json.parseToJsonElement(cardJson) as JsonObject)

    private val keyPattern = Regex("""widget\.(\d+)\.""")

    private fun widgetIdFromKey(name: String): Int? =
      keyPattern.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
  }
}
