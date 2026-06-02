package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot

/**
 * Composition local that lets stack / grid / conditional cards recurse into the registry for their
 * children without having to thread it through every `Render` call.
 *
 * Dashboard entry point (`DashboardRenderer`) installs this via [ProvideCardRegistry].
 */
val LocalCardRegistry =
  staticCompositionLocalOf<CardRegistry> {
    error("No CardRegistry in scope — wrap with ProvideCardRegistry { }.")
  }

@Composable
fun ProvideCardRegistry(registry: CardRegistry, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalCardRegistry provides registry, content = content)
}

/**
 * Dispatch a child card to its registered converter. Unknown types fall through to an Unsupported
 * placeholder so the rest of the view still renders.
 */
@Composable
fun RenderChild(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier = RemoteModifier) {
  val registry = LocalCardRegistry.current
  val converter = registry.get(card.type) ?: registry.get(UNSUPPORTED_CARD_TYPE) ?: return
  converter.Render(card, snapshot, modifier)
}

/**
 * Host-side lookup of a card's preferred rendered height in dp. The dashboard does **not** call
 * this — its slots size to the document's intrinsic content via `WrapAdaptiveRemoteDocumentPlayer`.
 * The hint is consumed by hosts that need a fixed pixel size up front: Glance widget bitmap
 * captures and the widget install preview. Unknown card types fall back to the
 * unsupported-placeholder converter's height; failing that, 160.
 */
fun CardRegistry.cardHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
  val converter = get(card.type) ?: get(UNSUPPORTED_CARD_TYPE)
  return converter?.naturalHeightDp(card, snapshot) ?: 160
}

/**
 * Host-side lookup of a card's [CardWidthClass]. Unknown / unsupported cards fall back to
 * [CardWidthClass.Full] so a placeholder slot spans the row rather than visually pairing with a
 * real card.
 */
fun CardRegistry.cardWidthClass(card: CardConfig, snapshot: HaSnapshot): CardWidthClass {
  val converter = get(card.type) ?: get(UNSUPPORTED_CARD_TYPE)
  return converter?.naturalWidthClass(card, snapshot) ?: CardWidthClass.Full
}

/**
 * Host-side lookup of a card's [WidgetSizeConstraints] — the comfortable default size used to pick
 * a launcher widget's cell. Unknown / unsupported cards fall back to the unsupported-placeholder
 * converter's size; failing that, a conservative full-width default.
 */
fun CardRegistry.cardSizeConstraints(
  card: CardConfig,
  snapshot: HaSnapshot,
): WidgetSizeConstraints {
  val converter = get(card.type) ?: get(UNSUPPORTED_CARD_TYPE)
  return converter?.sizeConstraints(card, snapshot)
    ?: WidgetSizeConstraints(defaultWidthDp = 320, defaultHeightDp = 160)
}

/**
 * Host-side lookup of a card's [CardConverter.dataSignature] — the fingerprint of baked,
 * non-bindable content used to invalidate the render cache key. Null when the card keeps up through
 * named bindings (the common case) or its type is unknown. The dashboard folds a non-null result
 * into the card's cache key and skips the live-binding push for that card.
 */
fun CardRegistry.cardDataSignature(card: CardConfig, snapshot: HaSnapshot): String? =
  get(card.type)?.dataSignature(card, snapshot)

internal const val UNSUPPORTED_CARD_TYPE = "__unsupported__"
