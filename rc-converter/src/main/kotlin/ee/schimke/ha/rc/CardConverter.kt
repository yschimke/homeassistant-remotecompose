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

    /**
     * Preferred rendered height in dp when this card fills its parent
     * width. **Hint only**: dashboard slots no longer pin to this — the
     * RemoteCompose document's intrinsic content height drives the
     * Compose layout via `WrapAdaptiveRemoteDocumentPlayer`. The hint
     * is still consumed by hosts that need an explicit pixel size up
     * front: bitmap captures for Glance widgets
     * (`TerrazzoWidgetProvider`, `MonitoringService`), the widget
     * install preview (`WidgetInstallSheet`), and the sizing
     * experiment previews (slack/tight slot variants).
     *
     * The default is a catch-all; each converter should override with
     * a value that matches its HA reference capture. If a card's
     * height depends on its payload (e.g. `entities` with N rows),
     * override to compute from [card] / [snapshot].
     */
    fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 160

    /**
     * Whether this card prefers to share its row with siblings
     * ([CardWidthClass.Compact] — small button-shaped tiles like `tile`,
     * `button`, `entity`) or wants the full content width
     * ([CardWidthClass.Full] — list-shaped, hero, or chrome cards).
     *
     * The dashboard layout reads this to pack consecutive Compact
     * cards into a single row on tablets / unfolded foldables, while
     * keeping Full cards stacked one-per-row regardless of width.
     * Default is [CardWidthClass.Full]: the conservative choice that
     * preserves how the card was authored.
     */
    fun naturalWidthClass(card: CardConfig, snapshot: HaSnapshot): CardWidthClass =
        CardWidthClass.Full

    /**
     * The comfortable default size this card wants when pinned as a
     * launcher widget, in dp — the cell it should occupy before the
     * user resizes it.
     *
     * This is the **per-card supported-size contract**: it answers "what
     * size should this widget pin at?" so the host can offer a sensible
     * default cell. We deliberately don't express hard min/max bounds —
     * the launcher's own grid governs how small or large a widget can
     * get, and the card's Fixed-mode breakpoint ladders adapt to
     * whatever cell results. On the phone, `WidgetSizeClass` maps this
     * default onto a provider variant whose `targetCell*` matches.
     *
     * The default derives from [naturalWidthClass] (Compact cards pin
     * small and near-square; Full cards pin wide) and [naturalHeightDp]
     * (so payload-sized cards like `entities` advertise a taller default
     * as rows are added). Override when a card's comfortable size doesn't
     * follow from those two.
     */
    fun sizeConstraints(card: CardConfig, snapshot: HaSnapshot): WidgetSizeConstraints {
        val height = naturalHeightDp(card, snapshot)
        return when (naturalWidthClass(card, snapshot)) {
            CardWidthClass.Compact ->
                WidgetSizeConstraints(
                    defaultWidthDp = 160,
                    defaultHeightDp = height.coerceAtLeast(40),
                )
            CardWidthClass.Full ->
                WidgetSizeConstraints(
                    defaultWidthDp = 320,
                    defaultHeightDp = height.coerceAtLeast(48),
                )
        }
    }
}

/**
 * A card's comfortable default launcher-widget size, in dp. Emitted by
 * [CardConverter.sizeConstraints] and mapped onto a host's discrete
 * cells (on the phone, a `WidgetSizeClass` provider variant). It's a
 * preferred default, not a hard bound — resize beyond it is up to the
 * launcher; the card's breakpoint ladders adapt to whatever results.
 */
data class WidgetSizeConstraints(
    val defaultWidthDp: Int,
    val defaultHeightDp: Int,
) {
    init {
        require(defaultWidthDp > 0 && defaultHeightDp > 0) {
            "default size must be positive: $defaultWidthDp x $defaultHeightDp"
        }
    }
}

/**
 * Layout hint emitted by a [CardConverter]. The dashboard renderer uses
 * it to decide whether the card stays on its own row or pairs up with
 * neighbouring small cards.
 */
enum class CardWidthClass {
    /** Small button-shaped card (tile, button, entity). Pairs into rows. */
    Compact,
    /** Wants the full content width — list / hero / chrome. */
    Full,
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
