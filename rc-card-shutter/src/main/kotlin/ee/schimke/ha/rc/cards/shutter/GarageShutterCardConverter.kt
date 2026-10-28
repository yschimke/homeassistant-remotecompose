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
 * Converter for `custom:garage-shutter-card`.
 *
 * Mirrors [EnhancedShutterCardConverter] for window shutters but targets garage doors:
 *
 * - sectional-door visualisation (horizontal aspect, panel grooves),
 * - direction-of-motion chevron driven by the cover's `opening` / `closing` state,
 * - up / stop / down buttons that follow the door ([coverControls]): close is greyed on a closed
 *   door, open on an open one, stop unless it is moving, and unsupported ones are hidden,
 * - one state line ([coverStateLabel]): "Closed", "Open", or "Opening · 40%".
 *
 * Position semantics match the shutter card so the two cards' fractions can share host-side
 * animation code: `closedFraction` 0..1 where 1 is fully closed. HA's `current_position` is "100 =
 * fully open" so we invert it once at the converter boundary.
 *
 * Register via [CardRegistry.withGarageShutter] (or both at once via [withAllShutters]).
 */
class GarageShutterCardConverter : CardConverter {
  override val cardType: String = CARD_TYPE

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
    val hasTitle = card.raw["title"]?.jsonPrimitive?.content?.isNotBlank() == true
    val entryCount = CoverDesign.rowsOf(card).size.coerceAtLeast(1)
    // Per-row: 13dp name + 4dp + 72dp viz + 4dp + 11dp state ≈ 104dp,
    // plus 10dp inter-row gap. Title + outer padding adds ~28dp + 20dp.
    return (if (hasTitle) 28 else 0) + 20 + entryCount * 114
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val title = card.raw["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    RemoteHaGarage(HaGarageCardData(title, entries(card, snapshot)), modifier)
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
      garage = { true },
      hide = { CoverHideStates.None },
      unknownFraction = { 1f },
    )

  companion object {
    const val CARD_TYPE: String = "custom:garage-shutter-card"
  }
}

/**
 * Convenience: register [GarageShutterCardConverter] on this registry. Mirrors the shape of
 * [withEnhancedShutter] so consumers can chain either or both.
 */
fun CardRegistry.withGarageShutter(): CardRegistry = apply {
  register(GarageShutterCardConverter())
}

/**
 * Convenience: register both shutter-family converters at once. Most dashboards that want the
 * window shutter also want the garage card, and vice-versa, so wire them with one call.
 */
fun CardRegistry.withAllShutters(): CardRegistry = apply {
  register(EnhancedShutterCardConverter())
  register(GarageShutterCardConverter())
}
