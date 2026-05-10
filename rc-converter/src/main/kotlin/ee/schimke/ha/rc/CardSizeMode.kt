package ee.schimke.ha.rc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How a card converter should size its content.
 *
 * The flag is recorded into the captured RemoteCompose document — converters
 * that read [LocalCardSizeMode] emit different content under [Wrap] vs
 * [Fixed], and the resulting `.rc` bytes carry that decision baked in. To
 * change a card from one mode to another the document has to be re-recorded.
 * That's intentional: the choice is always known at recording time for the
 * three surfaces we ship today (mobile dashboard, mobile launcher widget,
 * wear widget), so a runtime knob would buy nothing.
 */
enum class CardSizeMode {
    /**
     * The template decides what to show; the captured document's intrinsic
     * size flows back to the host. Width is typically pinned by the parent
     * (dashboard slot, sheet, etc.) and height grows to fit the content.
     * Wrap-mode rendering should match the Home Assistant reference card as
     * closely as reasonable.
     *
     * Used by the mobile dashboard and the in-app preview surfaces.
     */
    Wrap,

    /**
     * The host gives the card a runtime-determined size and the card adapts
     * to it. Adaptation typically uses `RemoteStateLayout` to switch between
     * breakpoint variants based on `RemoteFloatContext.componentWidth()` /
     * `componentHeight()`, or hides elements that wouldn't fit. The card is
     * still recorded at one container size; adaptation happens at playback.
     *
     * Used by the launcher widget (`TerrazzoWidgetProvider`) and the Wear
     * slot widgets (`SlotWidget`), where the host (launcher / Glance Wear
     * tile surface) decides the canvas size.
     */
    Fixed,
}

/**
 * Composition local carrying the active [CardSizeMode] through a
 * RemoteCompose capture. Defaults to [CardSizeMode.Wrap] so converters that
 * don't opt into fixed-mode adaptation behave the same way they always have.
 *
 * Set via [ProvideCardSizeMode] at the capture root, mirroring how
 * [LocalCardRegistry] is provided via [ProvideCardRegistry].
 */
val LocalCardSizeMode = staticCompositionLocalOf { CardSizeMode.Wrap }

/**
 * Wrap [content] so converters inside it observe [mode] via
 * [LocalCardSizeMode]. Phone widget / wear widget capture sites pass
 * [CardSizeMode.Fixed]; the dashboard inherits the [CardSizeMode.Wrap]
 * default and doesn't need to provide.
 */
@Composable
fun ProvideCardSizeMode(mode: CardSizeMode, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCardSizeMode provides mode, content = content)
}
