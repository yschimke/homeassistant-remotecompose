package ee.schimke.ha.rc.cards.shutter

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.CardBindingRecipe
import ee.schimke.ha.design.CardBindingRecipes
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.components.HaAction
import kotlinx.serialization.json.jsonPrimitive

/**
 * Converter for the HACS `custom:enhanced-shutter-card`.
 *
 * Minimal port: renders per-entity rows with a window-shutter drawing (a garage door for
 * `device_class: garage` / `gate`) and open / stop / close buttons. The buttons follow the cover
 * ([coverControls]: greyed when they would do nothing, hidden when unsupported) and honour the
 * upstream `button_up_hide_states` / `button_stop_hide_states` / `button_down_hide_states`. The
 * rest of the upstream card (image-based slats & backgrounds, 3D tilt, presets, per-entity
 * resizing) is deliberately deferred.
 *
 * Register via [CardRegistry.register]:
 * ```kotlin
 * val registry = defaultRegistry().apply {
 *     register(EnhancedShutterCardConverter())
 * }
 * ```
 */
class EnhancedShutterCardConverter : CardConverter {
  override val cardType: String = CARD_TYPE

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
    val hasTitle = card.raw["title"]?.jsonPrimitive?.content?.isNotBlank() == true
    val entryCount = CoverDesign.rowsOf(card).size.coerceAtLeast(1)
    // ~140dp per shutter row (viz + name + state + gaps); plus title.
    return (if (hasTitle) 28 else 0) + 20 + entryCount * 140
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val title = card.raw["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    RemoteHaShutter(HaShutterCardData(title, entries(card, snapshot)), modifier)
  }

  /**
   * Every value the card shows is a binding ([CoverLive]), so a card of this [CoverDesign] shape
   * renders from a card design, someone's own included.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    CoverDesign.properties(card, snapshot, entries(card, snapshot))

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    CoverDesign.template(cardType, properties)

  override fun designRecipe(properties: DesignProperties): CardBindingRecipe? =
    CoverDesign.recipe(properties)

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings? =
    CardBindingRecipes.cover(CoverDesign.rowsOf(card).size).evaluate(card, snapshot)

  override fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: HaAction,
  ): HaAction? = CoverDesign.action(entries(card, snapshot ?: HaSnapshot()), slot, templateAction)

  private fun entries(card: CardConfig, snapshot: HaSnapshot): List<CoverEntryData> =
    CoverDesign.entries(
      card,
      snapshot,
      garage = ::isGarageLike,
      hide = CoverHideStates::fromEntry,
      unknownFraction = { garage -> if (garage) 1f else 0.5f },
    )

  companion object {
    const val CARD_TYPE: String = "custom:enhanced-shutter-card"
  }
}

/**
 * Convenience: `defaultRegistry()` from `:rc-converter` plus this module's converter. Consumers
 * that want the built-in cards and the shutter can use this one call instead of registering by
 * hand.
 */
fun CardRegistry.withEnhancedShutter(): CardRegistry = apply {
  register(EnhancedShutterCardConverter())
}
