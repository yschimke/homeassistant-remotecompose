package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaClock
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.SystemHaClock
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.ProvideChildCardBindings
import ee.schimke.ha.rc.components.RemoteHaGrid
import ee.schimke.ha.rc.components.RemoteHaHorizontalStack
import ee.schimke.ha.rc.components.RemoteHaVerticalStack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Layout-only cards. Each unrolls its `cards:` array through `RenderChild`, which dispatches to the
 * registered converter per child.
 */
class VerticalStackCardConverter : CardConverter {
  override val cardType: String = CardTypes.VERTICAL_STACK

  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    CompositeDesign.properties(card, snapshot)

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    CompositeDesign.template(cardType, properties)

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings =
    CompositeDesign.bindings(card, snapshot, SystemHaClock)

  override fun designBindings(
    card: CardConfig,
    snapshot: HaSnapshot,
    clock: HaClock,
  ): CardSnapshotBindings = CompositeDesign.bindings(card, snapshot, clock)

  override fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: HaAction,
  ): HaAction? = CompositeDesign.action(card, snapshot, slot, templateAction)

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    RemoteHaVerticalStack(modifier = modifier) { RenderChildren(card, snapshot) }
  }
}

class HorizontalStackCardConverter : CardConverter {
  override val cardType: String = CardTypes.HORIZONTAL_STACK

  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    CompositeDesign.properties(card, snapshot)

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    CompositeDesign.template(cardType, properties)

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings =
    CompositeDesign.bindings(card, snapshot, SystemHaClock)

  override fun designBindings(
    card: CardConfig,
    snapshot: HaSnapshot,
    clock: HaClock,
  ): CardSnapshotBindings = CompositeDesign.bindings(card, snapshot, clock)

  override fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: HaAction,
  ): HaAction? = CompositeDesign.action(card, snapshot, slot, templateAction)

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    RemoteHaHorizontalStack(modifier = modifier) { RenderChildren(card, snapshot) }
  }
}

class GridCardConverter : CardConverter {
  override val cardType: String = CardTypes.GRID

  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    CompositeDesign.properties(card, snapshot)

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    CompositeDesign.template(cardType, properties)

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings =
    CompositeDesign.bindings(card, snapshot, SystemHaClock)

  override fun designBindings(
    card: CardConfig,
    snapshot: HaSnapshot,
    clock: HaClock,
  ): CardSnapshotBindings = CompositeDesign.bindings(card, snapshot, clock)

  override fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: HaAction,
  ): HaAction? = CompositeDesign.action(card, snapshot, slot, templateAction)

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    RemoteHaGrid(modifier = modifier) { RenderChildren(card, snapshot) }
  }
}

/** Each child, as child k of this card for its card-level template bindings. */
@Composable
private fun RenderChildren(card: CardConfig, snapshot: HaSnapshot) {
  childCards(card).forEachIndexed { k, child ->
    ProvideChildCardBindings(k) { RenderChild(child, snapshot) }
  }
}

private fun childCards(card: CardConfig): List<CardConfig> =
  card.raw["cards"]
    ?.jsonArray
    ?.mapNotNull { it as? JsonObject }
    ?.mapNotNull { obj ->
      val type = obj["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
      CardConfig(type = type, raw = obj)
    } ?: emptyList()
