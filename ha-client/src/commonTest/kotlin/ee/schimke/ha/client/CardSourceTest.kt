package ee.schimke.ha.client

import ee.schimke.ha.model.CardBytes
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardKey
import ee.schimke.ha.model.CardSize
import ee.schimke.ha.model.ClientProfile
import ee.schimke.ha.model.HaSnapshot
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The chain has only a handful of behaviours but each one is the load-
 * bearing assumption for "generator choice is invisible to the user".
 *
 * - first-supports-and-produces wins;
 * - first-supports-but-returns-null falls through;
 * - !supports() skips without I/O;
 * - all-null → Unsupported (never throw).
 *
 * Priority is honoured by sorting at construction time, not at every
 * render — a fake with a low number must execute before one with a
 * high number regardless of insertion order.
 */
class CardSourceTest {

    private val key = CardKey(
        dashboardUrlPath = "lovelace",
        viewPath = "home",
        cardIndex = 0,
        type = "tile",
    )
    private val card = CardConfig(type = "tile", raw = JsonObject(emptyMap()))
    private val snapshot = HaSnapshot()
    private val size = CardSize(widthPx = 320, heightPx = 160)

    @Test
    fun render_picksLowestPriorityThatProduces() = runTest {
        val firstBytes = CardBytes(byteArrayOf(1), 320, 160)
        val secondBytes = CardBytes(byteArrayOf(2), 320, 160)
        val source = CardSource(
            listOf(
                fake("second", priority = 100, result = secondBytes),
                fake("first", priority = 0, result = firstBytes),
            ),
        )
        val result = source.render(key, card, snapshot, size, ClientProfile.Phone)
        assertIs<CardRender.Bytes>(result)
        assertEquals("first", result.generator)
        assertEquals(firstBytes, result.card)
    }

    @Test
    fun render_fallsThrough_whenHigherPriorityReturnsNull() = runTest {
        val secondBytes = CardBytes(byteArrayOf(2), 320, 160)
        val source = CardSource(
            listOf(
                fake("addon", priority = 0, result = null),
                fake("local", priority = 100, result = secondBytes),
            ),
        )
        val result = source.render(key, card, snapshot, size, ClientProfile.Phone)
        assertIs<CardRender.Bytes>(result)
        assertEquals("local", result.generator)
    }

    @Test
    fun render_skipsGeneratorsThatDontSupport() = runTest {
        val callRecord = mutableListOf<String>()
        val notSupporting = object : CardGenerator {
            override val name: String = "no-support"
            override val priority: Int = 0
            override fun supports(card: CardConfig, profile: ClientProfile): Boolean = false
            override suspend fun generate(
                key: CardKey, card: CardConfig, snapshot: HaSnapshot,
                size: CardSize, profile: ClientProfile,
            ): CardBytes? {
                callRecord += "no-support"
                return null
            }
        }
        val supporting = fake("local", priority = 100, result = CardBytes(byteArrayOf(7), 1, 1)) {
            callRecord += "local"
        }
        val source = CardSource(listOf(notSupporting, supporting))
        val result = source.render(key, card, snapshot, size, ClientProfile.Phone)
        assertIs<CardRender.Bytes>(result)
        assertEquals(listOf("local"), callRecord)
    }

    @Test
    fun render_returnsUnsupported_whenNoGeneratorProduces() = runTest {
        val source = CardSource(
            listOf(
                fake("addon", priority = 0, result = null),
                fake("local", priority = 100, result = null),
            ),
        )
        val result = source.render(key, card, snapshot, size, ClientProfile.Phone)
        assertIs<CardRender.Unsupported>(result)
        assertEquals("tile", result.cardType)
    }

    @Test
    fun render_returnsUnsupported_whenChainEmpty() = runTest {
        val source = CardSource(emptyList())
        val result = source.render(key, card, snapshot, size, ClientProfile.Phone)
        assertIs<CardRender.Unsupported>(result)
    }

    @Test
    fun generators_areSortedByPriorityAtConstruction() {
        val source = CardSource(
            listOf(
                fake("c", priority = 100, result = null),
                fake("a", priority = 0, result = null),
                fake("b", priority = 50, result = null),
            ),
        )
        assertEquals(listOf("a", "b", "c"), source.generators.map { it.name })
    }

    private fun fake(
        name: String,
        priority: Int,
        result: CardBytes?,
        onGenerate: () -> Unit = {},
    ): CardGenerator = object : CardGenerator {
        override val name: String = name
        override val priority: Int = priority
        override fun supports(card: CardConfig, profile: ClientProfile): Boolean = true
        override suspend fun generate(
            key: CardKey, card: CardConfig, snapshot: HaSnapshot,
            size: CardSize, profile: ClientProfile,
        ): CardBytes? {
            onGenerate()
            return result
        }
    }
}
