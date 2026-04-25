@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.content.Context
import ee.schimke.ha.client.CardGenerator
import ee.schimke.ha.model.CardBytes
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardKey
import ee.schimke.ha.model.CardSize
import ee.schimke.ha.model.ClientProfile
import ee.schimke.ha.model.HaSnapshot

/**
 * [CardGenerator] backed by the in-process [CardRegistry] + the
 * Android-flavoured `rc-converter` capture path. Always last in the
 * chain (priority 100) so it's the safety net when no add-on is
 * configured or the add-on can't render a particular card.
 *
 * Today: ignores [ClientProfile] — converters render the same bytes
 * for every surface. Profile-aware rendering is a later milestone;
 * when it lands, [supports] will narrow the chain per profile.
 */
class LocalCardGenerator(
    private val context: Context,
    private val registry: CardRegistry,
) : CardGenerator {

    override val name: String = "local"
    override val priority: Int = 100

    override fun supports(card: CardConfig, profile: ClientProfile): Boolean =
        registry.get(card.type) != null

    override suspend fun generate(
        key: CardKey,
        card: CardConfig,
        snapshot: HaSnapshot,
        size: CardSize,
        profile: ClientProfile,
    ): CardBytes? = runCatching {
        val doc = captureCardDocument(
            context = context,
            widthPx = size.widthPx,
            heightPx = size.heightPx,
            densityDpi = size.densityDpi,
            registry = registry,
            card = card,
            snapshot = snapshot,
        )
        CardBytes(bytes = doc.bytes, widthPx = doc.widthPx, heightPx = doc.heightPx)
    }.getOrNull()
}
