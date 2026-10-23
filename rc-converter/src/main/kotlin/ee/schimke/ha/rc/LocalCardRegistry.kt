package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.LocalEntityAvailability
import ee.schimke.ha.rc.components.LocalRemoteAnimationsEnabled
import ee.schimke.ha.rc.components.ProvideExpressionNames
import ee.schimke.ha.rc.components.RemoteHaUnavailableFrame
import ee.schimke.ha.rc.icons.HaIconMap

/**
 * Composition local that lets stack / grid / conditional cards recurse into the registry for their
 * children without having to thread it through every `Render` call.
 *
 * Dashboard entry point (`DashboardRenderer`) installs this via [ProvideCardRegistry].
 */
val LocalCardRegistry =
  staticCompositionLocalOf<CardRegistry> {
    error("No CardRegistry in scope ? wrap with ProvideCardRegistry { }.")
  }

@Composable
fun ProvideCardRegistry(registry: CardRegistry, content: @Composable () -> Unit) {
  // A frozen clock (previews / tests provide a [FixedHaClock]) also freezes value tweens: the
  // encoded document carries the settled value with no startup animation, so a screenshot capture
  // can't land mid-tween and drift the PNG between runs. Production's [SystemHaClock] leaves
  // `isFrozen == false`, so live host writebacks keep animating. Same contract the clock card uses
  // for its static-label path ? see [LocalRemoteAnimationsEnabled].
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
  ProvideExpressionNames { RenderCard(card, snapshot, modifier) }
}

@Composable
private fun RenderCard(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
  val registry = LocalCardRegistry.current
  val converter = registry.get(card.type) ?: registry.get(UNSUPPORTED_CARD_TYPE) ?: return
  // A conditional is only a wrapper: the card it shows gets the frame, as if it stood alone.
  if (card.type == CardTypes.CONDITIONAL && !LocalInsideCard.current) {
    converter.Render(card, snapshot, modifier)
    return
  }
  FramedCard(card, snapshot, modifier) { converter.Render(card, snapshot, it) }
}

/**
 * [content] for a top-level [card], inside the unavailable frame of the one entity it is about: the
 * frame [RenderChild] gives every card type, for a card drawn by something other than its converter
 * (a card design authored in UI Builder). [content] gets the modifier to draw with.
 */
@Composable
fun FramedCard(
  card: CardConfig,
  snapshot: HaSnapshot,
  modifier: RemoteModifier = RemoteModifier,
  content: @Composable (RemoteModifier) -> Unit,
) {
  val entityId = if (LocalInsideCard.current) null else unavailableFrameEntity(card)
  if (entityId == null) {
    // No frame here: a stack or grid with no entity of its own leaves each child to frame itself.
    content(modifier)
    return
  }
  // The card is about one entity: it shows that entity's unavailability as the uber theme does,
  // once for every card type rather than in each converter.
  val initiallyAvailable =
    snapshot.states[entityId]?.let { HaLiveBindings.isAvailable(it.state) } ?: false
  val isAvailable =
    if (LocalEntityAvailability.current) LiveValues.isAvailable(entityId, initiallyAvailable)
    else null
  RemoteHaUnavailableFrame(isAvailable, initiallyAvailable, modifier) { contentModifier ->
    CompositionLocalProvider(LocalInsideCard provides true) { content(contentModifier) }
  }
}

/**
 * True while composing inside an unavailable frame, so a card nested in a framed card (a stack or
 * grid with its own `entity:`, which renders children through [RenderChild] again) doesn't get a
 * second frame of its own. A container without an entity has no frame and doesn't set it, so its
 * children frame themselves.
 */
private val LocalInsideCard = staticCompositionLocalOf { false }

/**
 * The one entity a top-level [card] is about (its `entity:`), whose unavailability the card shows
 * with the uber theme's [ee.schimke.ha.rc.components.HaUnavailableStyle], or null for a card about
 * several entities or none (an entities list dims each row instead).
 */
internal fun unavailableFrameEntity(card: CardConfig): String? =
  (card.raw["entity"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { '.' in it }

/**
 * Host-side lookup of a card's preferred rendered height in dp. The dashboard does **not** call
 * this ? its slots size to the document's intrinsic content via `CachedCardPreview`'s player. The
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
 * Host-side lookup of a card's [CardConverter.dataSignature] ? the fingerprint of baked,
 * non-bindable content used to invalidate the render cache key. Null when the card keeps up through
 * named bindings (the common case) or its type is unknown. The dashboard folds a non-null result
 * into the card's cache key and skips the live-binding push for that card.
 */
fun CardRegistry.cardDataSignature(card: CardConfig, snapshot: HaSnapshot): String? =
  get(card.type)?.dataSignature(card, snapshot, this)

/**
 * The signature of a container's [children] (stack, grid): each child's [cardDataSignature], tagged
 * with its position, joined in order ? or null when every child keeps up through named bindings, so
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
 * `white-balance-sunny` / `weather-night`, an alarm panel's `shield-home` / `shield-off`, ?), or
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
