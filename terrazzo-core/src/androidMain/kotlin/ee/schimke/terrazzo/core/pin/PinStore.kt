package ee.schimke.terrazzo.core.pin

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Mobile-side pin store. Source of truth for the user's pinned
 * dashboard items — both individual cards and whole sections — that the
 * watch surfaces as nav destinations and that drive the wear-widget
 * slot picker.
 *
 * Independent of [ee.schimke.terrazzo.core.widget.WidgetStore], which
 * keeps tracking phone home-screen widget installs separately. A user
 * who installs a card as a phone widget does not implicitly pin it for
 * the watch, and vice versa.
 *
 * Storage: a single Preferences DataStore with two JSON-encoded list
 * entries (`pinned_cards` / `pinned_sections`). JSON-in-prefs is enough
 * for the small cardinalities involved (handful of pins) and avoids
 * adding a second DataStore proto.
 *
 * Each pin owns:
 *   - a stable `key` (used by [WearWidgetSlots] slot references and as
 *     the de-dup token for is-pinned checks),
 *   - an `orderIndex` shared across cards and sections so the user
 *     controls the unified Wear top-level ordering as a single list,
 *   - `baseUrl` and dashboard/section provenance so the manage-pinned
 *     UI can show "where did this come from" and the wear sync layer
 *     can scope updates to the active session.
 *
 * Section content (the actual card configs the watch renders) is
 * captured into [PinnedCardData] at pin-time and stored alongside the
 * section. This keeps the Wear publisher self-contained and avoids
 * round-tripping the dashboard on every push. If the user edits the
 * section in HA the captured copy goes stale until they re-pin — an
 * acceptable v1 trade.
 */
@SingleIn(AppScope::class)
@Inject
class PinStore(private val context: Context) {

    val cards: Flow<List<MobilePinnedCard>>
        get() = context.store.data.map { it.readCards() }

    val sections: Flow<List<MobilePinnedSection>>
        get() = context.store.data.map { it.readSections() }

    suspend fun cardsNow(): List<MobilePinnedCard> = cards.first()

    suspend fun sectionsNow(): List<MobilePinnedSection> = sections.first()

    /** Whether a card with [cardKey] is currently pinned. */
    fun isCardPinned(cardKey: String): Flow<Boolean> =
        context.store.data.map { prefs -> prefs.readCards().any { it.key == cardKey } }

    /** Whether a section with [sectionKey] is currently pinned. */
    fun isSectionPinned(sectionKey: String): Flow<Boolean> =
        context.store.data.map { prefs -> prefs.readSections().any { it.key == sectionKey } }

    /**
     * Pin a card. Idempotent — re-pinning the same key updates the
     * captured payload (e.g. title changed) without changing its order
     * position. New pins land at the end of the unified ordering.
     */
    suspend fun pinCard(card: MobilePinnedCard) {
        context.store.edit { prefs ->
            val cards = prefs.readCards().toMutableList()
            val existing = cards.indexOfFirst { it.key == card.key }
            if (existing >= 0) {
                cards[existing] = card.copy(orderIndex = cards[existing].orderIndex)
            } else {
                cards += card.copy(orderIndex = nextOrderIndex(prefs))
            }
            prefs.writeCards(cards)
        }
    }

    suspend fun unpinCard(cardKey: String) {
        context.store.edit { prefs ->
            val cards = prefs.readCards().filterNot { it.key == cardKey }
            prefs.writeCards(cards)
        }
    }

    /** Pin a section. Same idempotent semantics as [pinCard]. */
    suspend fun pinSection(section: MobilePinnedSection) {
        context.store.edit { prefs ->
            val sections = prefs.readSections().toMutableList()
            val existing = sections.indexOfFirst { it.key == section.key }
            if (existing >= 0) {
                sections[existing] = section.copy(orderIndex = sections[existing].orderIndex)
            } else {
                sections += section.copy(orderIndex = nextOrderIndex(prefs))
            }
            prefs.writeSections(sections)
        }
    }

    suspend fun unpinSection(sectionKey: String) {
        context.store.edit { prefs ->
            val sections = prefs.readSections().filterNot { it.key == sectionKey }
            prefs.writeSections(sections)
        }
    }

    /**
     * Reorder the unified top-level list. [keysInOrder] is the desired
     * sequence of keys (cards and sections interleaved); items are
     * reassigned consecutive `orderIndex` values starting at 0.
     * Unknown keys are ignored. Items not present in [keysInOrder]
     * keep their relative order at the tail.
     */
    suspend fun reorder(keysInOrder: List<String>) {
        context.store.edit { prefs ->
            val cards = prefs.readCards()
            val sections = prefs.readSections()
            val cardByKey = cards.associateBy { it.key }
            val sectionByKey = sections.associateBy { it.key }
            val seen = mutableSetOf<String>()

            val orderedCards = mutableListOf<MobilePinnedCard>()
            val orderedSections = mutableListOf<MobilePinnedSection>()
            var next = 0
            for (key in keysInOrder) {
                if (!seen.add(key)) continue
                cardByKey[key]?.let { orderedCards += it.copy(orderIndex = next++) }
                sectionByKey[key]?.let { orderedSections += it.copy(orderIndex = next++) }
            }
            for (card in cards) {
                if (card.key in seen) continue
                orderedCards += card.copy(orderIndex = next++)
            }
            for (section in sections) {
                if (section.key in seen) continue
                orderedSections += section.copy(orderIndex = next++)
            }
            prefs.writeCards(orderedCards)
            prefs.writeSections(orderedSections)
        }
    }

    private fun nextOrderIndex(prefs: Preferences): Int {
        val maxCard = prefs.readCards().maxOfOrNull { it.orderIndex } ?: -1
        val maxSection = prefs.readSections().maxOfOrNull { it.orderIndex } ?: -1
        return maxOf(maxCard, maxSection) + 1
    }

    private fun Preferences.readCards(): List<MobilePinnedCard> =
        this[CARDS_KEY]?.let { runCatching { json.decodeFromString<List<MobilePinnedCard>>(it) }.getOrNull() }
            ?: emptyList()

    private fun Preferences.readSections(): List<MobilePinnedSection> =
        this[SECTIONS_KEY]?.let { runCatching { json.decodeFromString<List<MobilePinnedSection>>(it) }.getOrNull() }
            ?: emptyList()

    private fun androidx.datastore.preferences.core.MutablePreferences.writeCards(value: List<MobilePinnedCard>) {
        this[CARDS_KEY] = json.encodeToString(value)
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeSections(value: List<MobilePinnedSection>) {
        this[SECTIONS_KEY] = json.encodeToString(value)
    }

    companion object {
        private val Context.store by preferencesDataStore(name = "terrazzo_pins")
        private val CARDS_KEY = stringPreferencesKey("pinned_cards")
        private val SECTIONS_KEY = stringPreferencesKey("pinned_sections")
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Stable key for a pinned card. Derived from the source-of-truth
         * fields (HA instance + dashboard + raw card config) so that
         * re-pinning the same card from the same dashboard yields the
         * same key — slot assignments survive an unpin / re-pin cycle.
         */
        fun cardKey(baseUrl: String, dashboardUrlPath: String, cardJson: String): String =
            sha16("c|$baseUrl|$dashboardUrlPath|$cardJson")

        /**
         * Stable key for a pinned section. Sections have no inherent id
         * in HA's config so position is the key; if the user reorders
         * sections in HA the key changes and the user has to re-pin.
         */
        fun sectionKey(
            baseUrl: String,
            dashboardUrlPath: String,
            viewPath: String,
            sectionIndex: Int,
        ): String = sha16("s|$baseUrl|$dashboardUrlPath|$viewPath|$sectionIndex")

        private fun sha16(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            val sb = StringBuilder(16)
            for (i in 0 until 8) {
                sb.append(((digest[i].toInt() and 0xFF) or 0x100).toString(16).substring(1))
            }
            return sb.toString()
        }
    }
}

/**
 * One pinned card. Captured at pin-time so the wear sync layer doesn't
 * need to re-resolve the dashboard on publish.
 */
@Serializable
data class MobilePinnedCard(
    val key: String,
    val baseUrl: String,
    val dashboardUrlPath: String,
    val card: PinnedCardData,
    val orderIndex: Int = 0,
)

/**
 * One pinned section. [cards] is the section's contents at pin-time.
 */
@Serializable
data class MobilePinnedSection(
    val key: String,
    val baseUrl: String,
    val dashboardUrlPath: String,
    val viewPath: String,
    val sectionIndex: Int,
    val title: String,
    val cards: List<PinnedCardData> = emptyList(),
    val orderIndex: Int = 0,
)

/**
 * Card snapshot stored inside the pin store. Mirrors the wire shape of
 * `CardSummary` in the wear-sync proto without depending on it (this
 * module sits below the proto's host).
 */
@Serializable
data class PinnedCardData(
    val type: String = "",
    val title: String = "",
    val primaryEntity: String = "",
    val rawJson: String = "",
)
