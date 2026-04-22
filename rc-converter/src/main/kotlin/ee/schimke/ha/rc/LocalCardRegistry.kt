package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot

/**
 * Composition local that lets stack / grid / conditional cards recurse
 * into the registry for their children without having to thread it
 * through every `Render` call.
 *
 * Dashboard entry point (`DashboardRenderer`) installs this via
 * [ProvideCardRegistry].
 */
val LocalCardRegistry = staticCompositionLocalOf<CardRegistry> {
    error("No CardRegistry in scope — wrap with ProvideCardRegistry { }.")
}

@Composable
fun ProvideCardRegistry(registry: CardRegistry, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCardRegistry provides registry, content = content)
}

/**
 * Dispatch a child card to its registered converter. Unknown types fall
 * through to an Unsupported placeholder so the rest of the view still
 * renders.
 */
@Composable
fun RenderChild(
    card: CardConfig,
    snapshot: HaSnapshot,
    modifier: RemoteModifier = RemoteModifier,
) {
    val registry = LocalCardRegistry.current
    val converter = registry.get(card.type)
        ?: registry.get(UNSUPPORTED_CARD_TYPE)
        ?: return
    converter.Render(card, snapshot, modifier)
}

/**
 * Host-side lookup of a card's preferred rendered height in dp.
 * Callers pinning a container size (dashboard grid cell, widget
 * preview frame) use this instead of hand-rolling a per-type table.
 * Unknown card types fall back to the unsupported-placeholder
 * converter's height; failing that, 160.
 */
fun CardRegistry.cardHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
    val converter = get(card.type) ?: get(UNSUPPORTED_CARD_TYPE)
    return converter?.naturalHeightDp(card, snapshot) ?: 160
}

internal const val UNSUPPORTED_CARD_TYPE = "__unsupported__"
