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
     * The size band this card supports when pinned as a launcher
     * widget — its minimum legible size, the default it should pin at,
     * and the largest size worth growing to. Expressed in dp.
     *
     * This is the **per-card supported-size contract**: it answers
     * "how big can this widget be?" so the launcher can offer a
     * sensible default cell and constrain the resize handles
     * (`minResize*` / `maxResize*` in the `appwidget-provider`). Hosts
     * quantise the band onto whatever discrete cells they support — on
     * the phone, `WidgetSizeClass` maps it onto a provider variant.
     *
     * The default derives from [naturalWidthClass] (Compact cards get a
     * small, near-square band; Full cards a wide band that may grow
     * unbounded) and [naturalHeightDp] (the default height, so
     * payload-sized cards like `entities` automatically advertise a
     * taller default as rows are added). Override when a card's band
     * doesn't follow from those two — e.g. a card that must stay square,
     * or one with a hard maximum useful size.
     */
    fun sizeConstraints(card: CardConfig, snapshot: HaSnapshot): WidgetSizeConstraints {
        val height = naturalHeightDp(card, snapshot)
        return when (naturalWidthClass(card, snapshot)) {
            CardWidthClass.Compact ->
                WidgetSizeConstraints(
                    minWidthDp = 80,
                    minHeightDp = 40,
                    defaultWidthDp = 160,
                    defaultHeightDp = height.coerceAtLeast(40),
                    maxWidthDp = 250,
                    maxHeightDp = 250,
                )
            CardWidthClass.Full ->
                WidgetSizeConstraints(
                    minWidthDp = 160,
                    minHeightDp = 48,
                    defaultWidthDp = 320,
                    defaultHeightDp = height.coerceAtLeast(48),
                    maxWidthDp = WidgetSizeConstraints.UNBOUNDED,
                    maxHeightDp = WidgetSizeConstraints.UNBOUNDED,
                )
        }
    }
}

/**
 * Per-card supported-size band for launcher widgets, in dp. Emitted by
 * [CardConverter.sizeConstraints]. `min`/`max` bound the resize handles;
 * `default` is the cell the card pins at before the user resizes.
 *
 * A `max*` of [UNBOUNDED] means "no useful ceiling — let it grow with
 * the slot" (the widget surface background fills whatever cell results,
 * so over-tall list cards stay framed).
 */
data class WidgetSizeConstraints(
    val minWidthDp: Int,
    val minHeightDp: Int,
    val defaultWidthDp: Int,
    val defaultHeightDp: Int,
    val maxWidthDp: Int,
    val maxHeightDp: Int,
) {
    init {
        require(minWidthDp <= defaultWidthDp && defaultWidthDp <= maxWidthDp) {
            "width band must be min<=default<=max: $minWidthDp/$defaultWidthDp/$maxWidthDp"
        }
        require(minHeightDp <= defaultHeightDp && defaultHeightDp <= maxHeightDp) {
            "height band must be min<=default<=max: $minHeightDp/$defaultHeightDp/$maxHeightDp"
        }
    }

    companion object {
        /** Sentinel for an unbounded maximum dimension. */
        const val UNBOUNDED = Int.MAX_VALUE
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
