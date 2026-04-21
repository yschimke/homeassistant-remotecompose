package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot

/**
 * A converter that knows how to emit one Lovelace card type as
 * RemoteCompose content.
 *
 * Implementations run inside a `RemoteComposeWriter { ... }` capture — they
 * call `Remote*` composables only. The writer turns the captured tree into a
 * `.rc` byte stream that any RemoteCompose player can render.
 *
 * ## Why one converter per type (instead of automatic conversion)
 *
 * HA cards are lit-element web components that read `hass.states` directly
 * and call services. There is no shared render IR we can intercept from a
 * JVM process — the only way to "run" a card is in a browser. So this
 * library is a hand-written mapping, verified card-by-card against
 * screenshots of the real HA frontend.
 */
interface CardConverter {
    /** Lovelace `type:` value this converter handles, e.g. `"tile"`, or `"custom:mushroom-light"`. */
    val cardType: String

    /**
     * Invoked inside a RemoteCompose capture scope. Implementations emit
     * `Remote*` composables from [androidx.compose.remote.creation.compose].
     *
     * @param modifier applied to the card's top-level composable. Lets
     *   the caller set `fillMaxWidth()` when the card is rendered as a
     *   standalone dashboard tile, or leave it wrap-content when packed
     *   into a grid / horizontal-stack. Child converters inside a stack
     *   typically pass `RemoteModifier` (the default) so the stack's
     *   layout decides.
     */
    @Composable
    fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier = RemoteModifier)
}

/**
 * Look up converters by [CardConfig.type]. Unknown types fall back to a
 * placeholder so a single unsupported card in a view doesn't fail the whole
 * dashboard.
 */
class CardRegistry(converters: Iterable<CardConverter> = emptyList()) {
    private val byType: MutableMap<String, CardConverter> =
        converters.associateBy { it.cardType }.toMutableMap()

    fun register(converter: CardConverter) { byType[converter.cardType] = converter }

    fun get(type: String): CardConverter? = byType[type]

    fun supported(): Set<String> = byType.keys.toSet()
}
