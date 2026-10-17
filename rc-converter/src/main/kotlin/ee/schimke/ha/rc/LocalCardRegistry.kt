package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.LocalRemoteAnimationsEnabled
import ee.schimke.ha.rc.icons.HaIconMap

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
  // A frozen clock (previews / tests provide a [FixedHaClock]) also freezes value tweens: the
  // encoded document carries the settled value with no startup animation, so a screenshot capture
  // can't land mid-tween and drift the PNG between runs. Production's [SystemHaClock] leaves
  // `isFrozen == false`, so live host writebacks keep animating. Same contract the clock card uses
  // for its static-label path — see [LocalRemoteAnimationsEnabled].
  CompositionLocalProvider(
    LocalCardRegistry provides registry,
    LocalRemoteAnimationsEnabled provides !LocalHaClock.current.isFrozen,
    content = content,
  )
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
 * this — its slots size to the document's intrinsic content via `CachedCardPreview`'s player. The
 * hint is consumed by hosts that need a fixed pixel size up front: Glance widget bitmap captures
 * and the widget install preview. Unknown card types fall back to the unsupported-placeholder
 * converter's height; failing that, 160.
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
  get(card.type)?.dataSignature(card, snapshot, this)

/**
 * The signature of a container's [children] (stack, grid): each child's [cardDataSignature], tagged
 * with its position, joined in order — or null when every child keeps up through named bindings, so
 * a container of binding cards stays on the binding path. One re-encode child makes the whole
 * container document re-encode, since its bytes bake that child.
 */
fun CardRegistry.childrenDataSignature(children: List<CardConfig>, snapshot: HaSnapshot): String? {
  val parts = children.mapIndexedNotNull { i, child ->
    cardDataSignature(child, snapshot)?.let { "$i:${child.type}{$it}" }
  }
  return if (parts.isEmpty()) null else parts.joinToString("|")
}

/**
 * The state-following glyphs [card] draws ([HaIconMap.stateSignature] per entity: a sun's
 * `white-balance-sunny` / `weather-night`, an alarm panel's `shield-home` / `shield-off`, …), or
 * null when none of its entities' glyphs follow their state. Unlike [cardDataSignature], values
 * still arrive as named bindings: a host folds this into the card's cache key only, so the document
 * re-encodes when a glyph changes and keeps taking binding pushes otherwise. On/off glyphs aren't
 * in it; they swap in-document on `is_on` ([HaIconMap.entityIcons]).
 */
fun cardIconSignature(card: CardConfig, snapshot: HaSnapshot): String? =
  cardEntityIds(card)
    .mapNotNull { id -> HaIconMap.stateSignature(snapshot.states[id])?.let { "$id=$it" } }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(";")

internal const val UNSUPPORTED_CARD_TYPE = "__unsupported__"

/**
 * The converter that renders cards of [type]: its own, or the unsupported placeholder for a type
 * nothing registers (a custom card). The design path keys and captures through the same lookup, so
 * a custom card renders its placeholder from a design too.
 */
fun CardRegistry.converterFor(type: String): CardConverter? =
  get(type) ?: get(UNSUPPORTED_CARD_TYPE)
