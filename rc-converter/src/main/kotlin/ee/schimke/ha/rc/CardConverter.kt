package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaAction

/**
 * A converter that knows how to emit one Lovelace card type as RemoteCompose content.
 *
 * Implementations run inside a `RemoteComposeWriter { ... }` capture — they call `Remote*`
 * composables only. The writer turns the captured tree into a `.rc` byte stream that any
 * RemoteCompose player can render.
 *
 * ## Why one converter per type (instead of automatic conversion)
 *
 * HA cards are lit-element web components that read `hass.states` directly and call services. There
 * is no shared render IR we can intercept from a JVM process — the only way to "run" a card is in a
 * browser. So this library is a hand-written mapping, verified card-by-card against screenshots of
 * the real HA frontend.
 */
interface CardConverter {
  /** Lovelace `type:` value this converter handles, e.g. `"tile"`, or `"custom:mushroom-light"`. */
  val cardType: String

  /**
   * Invoked inside a RemoteCompose capture scope. Implementations emit `Remote*` composables from
   * [androidx.compose.remote.creation.compose].
   *
   * @param modifier applied to the card's top-level composable. Lets the caller set
   *   `fillMaxWidth()` when the card is rendered as a standalone dashboard tile, or leave it
   *   wrap-content when packed into a grid / horizontal-stack. Child converters inside a stack
   *   typically pass `RemoteModifier` (the default) so the stack's layout decides.
   */
  @Composable
  fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier = RemoteModifier)

  /**
   * A fingerprint of the snapshot-derived content this card *bakes* into its document, or null if
   * the card has no such content.
   *
   * This is the second half of the live-update contract. A card keeps up with Home Assistant in one
   * of two ways:
   * 1. **Named bindings (default).** The card binds host-reproducible values (`<id>.state`,
   *    `<id>.is_on`, `<id>.numeric_state`, `<id>.state_int`) via `LiveValues`, and a host pushes
   *    new values by name into the running player with no re-encode (see `cardSnapshotBindings`).
   *    Return null — the default.
   * 2. **Document re-encode.** The card bakes content a host can't reproduce from the snapshot
   *    alone — a per-card-formatted label, a forecast strip, a history sparkline, a
   *    to-do/calendar/logbook list, an arc fill fraction. Return a string that changes whenever
   *    that baked content changes (typically `cardDataSignature(cardEntityIds(card), snapshot)`).
   *    The dashboard folds it into the render cache key, so the document re-encodes on every
   *    relevant data change, and the host skips the named-binding push for this card (so it doesn't
   *    clobber the formatted bake with a raw value).
   *
   * Prefer (1): it's cheaper (no re-encode) and animates. Reach for (2) only when the content
   * genuinely can't be expressed as a binding.
   */
  fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String? = null

  /**
   * The generation properties of [card] under [snapshot], or null when this card can't render from
   * a card design (the default: it then captures through [Render]).
   *
   * Return only the **discrete** inputs that change the document [Render] emits — strings and enum
   * ints such as a domain, a `device_class` or an icon name, never a float or display text. Each
   * distinct set indexes its own template ([DesignProperties.shape] is the template part of the
   * design key), on the design server and in the local library. Everything else must reach the
   * document as a named binding (`<slot>.state`, `<slot>.name`, …).
   */
  fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? = null

  /**
   * The config and placeholder snapshot a template for [properties] is captured from: slot entity
   * ids (`CardDesignSlots.slotId`) in place of real ones, and the properties mapped back onto the
   * placeholder entity. Required whenever [designProperties] can return non-null.
   */
  fun designTemplate(properties: DesignProperties): DesignTemplateInput? = null

  /**
   * The card this card shows instead of itself, for [snapshot], when that choice depends on live
   * data the client holds — a conditional card's inner card while its conditions hold, an entity
   * filter's glance over the matching entities. The design path then keys, binds and taps the
   * delegate ([CardDesignTemplates.designCard]); the choice is the client's, made at playback, and
   * never part of a template.
   */
  fun designDelegate(card: CardConfig, snapshot: HaSnapshot): CardConfig? = null

  /**
   * Card-level bindings a design template of this card reads beyond the slot bindings, for
   * [snapshot]: config the template binds instead of baking (`card.<field>`, see `rc-components`'
   * `cardText` / `cardFloat`), or a slot value the card reads differently. They are pushed on top
   * of `CardDesignSlots.slotBindings`.
   */
  fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings? = null

  /**
   * [designBindings] with "now" read from [clock]. Template playback calls this one, passing the
   * [LocalHaClock] in scope, so a binding derived from the time (a clock card's date line) agrees
   * with what [Render] reads from the same clock on the direct path. A converter whose bindings
   * read the time overrides both forms, the two-argument one passing [SystemHaClock] (the
   * [LocalHaClock] default); every other converter overrides only the two-argument form.
   */
  fun designBindings(
    card: CardConfig,
    snapshot: HaSnapshot,
    clock: HaClock,
  ): CardSnapshotBindings? = designBindings(card, snapshot)

  /**
   * The action to run for a tap on slot [slot] of this card's design template, whose bytes encode
   * [templateAction] (with the slot's entity id), or null for the slot's default tap. Templates are
   * remote content, so an implementation never returns [templateAction] itself: it may only use it
   * to pick one of the actions [card]'s own config allows, rebuilt against the real entity and the
   * current [snapshot] — e.g. which ARM button or keypad key of an alarm panel was pressed.
   */
  fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: HaAction,
  ): HaAction? = null

  /**
   * Preferred rendered height in dp when this card fills its parent width. **Hint only**: dashboard
   * slots no longer pin to this — the RemoteCompose document's intrinsic content height drives the
   * Compose layout via `CachedCardPreview`'s player. The hint is still consumed by hosts that need
   * an explicit pixel size up front: bitmap captures for Glance widgets (`TerrazzoWidgetProvider`,
   * `MonitoringService`), the widget install preview (`WidgetInstallSheet`), and the sizing
   * experiment previews (slack/tight slot variants).
   *
   * The default is a catch-all; each converter should override with a value that matches its HA
   * reference capture. If a card's height depends on its payload (e.g. `entities` with N rows),
   * override to compute from [card] / [snapshot].
   */
  fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 160

  /**
   * Whether this card prefers to share its row with siblings ([CardWidthClass.Compact] — small
   * button-shaped tiles like `tile`, `button`, `entity`) or wants the full content width
   * ([CardWidthClass.Full] — list-shaped, hero, or chrome cards).
   *
   * The dashboard layout reads this to pack consecutive Compact cards into a single row on tablets
   * / unfolded foldables, while keeping Full cards stacked one-per-row regardless of width. Default
   * is [CardWidthClass.Full]: the conservative choice that preserves how the card was authored.
   */
  fun naturalWidthClass(card: CardConfig, snapshot: HaSnapshot): CardWidthClass =
    CardWidthClass.Full

  /**
   * The comfortable default size this card wants when pinned as a launcher widget, in dp — the cell
   * it should occupy before the user resizes it.
   *
   * This is the **per-card supported-size contract**: it answers "what size should this widget pin
   * at?" so the host can offer a sensible default cell. We deliberately don't express hard min/max
   * bounds — the launcher's own grid governs how small or large a widget can get, and the card's
   * Fixed-mode breakpoint ladders adapt to whatever cell results. On the phone, `WidgetSizeClass`
   * maps this default onto a provider variant whose `targetCell*` matches.
   *
   * The default derives from [naturalWidthClass] (Compact cards pin small and near-square; Full
   * cards pin wide) and [naturalHeightDp] (so payload-sized cards like `entities` advertise a
   * taller default as rows are added). Override when a card's comfortable size doesn't follow from
   * those two.
   */
  fun sizeConstraints(card: CardConfig, snapshot: HaSnapshot): WidgetSizeConstraints {
    val height = naturalHeightDp(card, snapshot)
    return when (naturalWidthClass(card, snapshot)) {
      CardWidthClass.Compact ->
        WidgetSizeConstraints(defaultWidthDp = 160, defaultHeightDp = height.coerceAtLeast(40))
      CardWidthClass.Full ->
        WidgetSizeConstraints(defaultWidthDp = 320, defaultHeightDp = height.coerceAtLeast(48))
    }
  }
}

/**
 * A card's comfortable default launcher-widget size, in dp. Emitted by
 * [CardConverter.sizeConstraints] and mapped onto a host's discrete cells (on the phone, a
 * `WidgetSizeClass` provider variant). It's a preferred default, not a hard bound — resize beyond
 * it is up to the launcher; the card's breakpoint ladders adapt to whatever results.
 */
data class WidgetSizeConstraints(val defaultWidthDp: Int, val defaultHeightDp: Int) {
  init {
    require(defaultWidthDp > 0 && defaultHeightDp > 0) {
      "default size must be positive: $defaultWidthDp x $defaultHeightDp"
    }
  }
}

/**
 * Layout hint emitted by a [CardConverter]. The dashboard renderer uses it to decide whether the
 * card stays on its own row or pairs up with neighbouring small cards.
 */
enum class CardWidthClass {
  /** Small button-shaped card (tile, button, entity). Pairs into rows. */
  Compact,
  /** Wants the full content width — list / hero / chrome. */
  Full,
}

/**
 * Look up converters by [CardConfig.type]. Unknown types fall back to a placeholder so a single
 * unsupported card in a view doesn't fail the whole dashboard.
 */
class CardRegistry(converters: Iterable<CardConverter> = emptyList()) {
  private val byType: MutableMap<String, CardConverter> =
    converters.associateBy { it.cardType }.toMutableMap()

  fun register(converter: CardConverter) {
    byType[converter.cardType] = converter
  }

  fun get(type: String): CardConverter? = byType[type]

  fun supported(): Set<String> = byType.keys.toSet()
}

/** What a card design template is captured from: see [CardConverter.designTemplate]. */
data class DesignTemplateInput(val card: CardConfig, val snapshot: HaSnapshot)
