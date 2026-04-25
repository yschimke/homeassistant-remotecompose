package ee.schimke.ha.client

import ee.schimke.ha.model.CardBytes
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardKey
import ee.schimke.ha.model.CardSize
import ee.schimke.ha.model.ClientProfile
import ee.schimke.ha.model.HaSnapshot

/**
 * [CardGenerator] backed by an [AddonClient] — fetches pre-rendered
 * bytes from the HA add-on's `/v1/cards/...` endpoint.
 *
 * Priority is the lowest of the built-ins (0): if an add-on is
 * configured and reachable, it gets the first crack at every card. The
 * server's typed 501 (until M3 lands) returns `null` from
 * [AddonClient.fetchCardBytes], which falls through to the next
 * generator in the chain (the local one).
 *
 * `supports` is intentionally optimistic — there's no client-side
 * registry of which card types the server can handle. Letting the
 * server respond is the cheapest way to find out.
 */
class AddonCardGenerator(
    private val client: AddonClient,
) : CardGenerator {

    override val name: String = "addon"
    override val priority: Int = 0

    override fun supports(card: CardConfig, profile: ClientProfile): Boolean = true

    override suspend fun generate(
        key: CardKey,
        card: CardConfig,
        snapshot: HaSnapshot,
        size: CardSize,
        profile: ClientProfile,
    ): CardBytes? {
        // Snapshot is unused on the wire — the add-on holds its own
        // state cache. State drift mitigation is a slice (b) concern.
        return client.fetchCardBytes(key, size, profile)
    }
}
