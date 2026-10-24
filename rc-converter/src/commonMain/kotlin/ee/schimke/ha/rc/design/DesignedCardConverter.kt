@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.design

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.DesignTemplateInput

/**
 * A card type migrated to its card design (server-owned-card-designs.md, "Migrating a card type"):
 * its layout is `card-designs/ui-builder/designs/<cardType>.uid`, packaged with this module
 * ([CardDesigns.design]), and its converter keeps only the card's key, bindings, actions and the
 * data its design's parts draw with.
 *
 * [Render] is final: which form shows at which size, on which surface, is the design's, so a change
 * to the layout is a change to the `.uid` (and to the parts it arranges), never a branch here. A
 * change made to a converter's old layout code instead, by a branch that predates the migration,
 * doesn't compile against this. `DesignedCardConvertersTest` holds the packaged designs and these
 * converters to each other.
 */
abstract class DesignedCardConverter : CardConverter {
  @Composable
  final override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val design =
      requireNotNull(CardDesigns.design(cardType)) { "no $cardType.uid packaged for $cardType" }
    CompositionLocalProvider(LocalCardInput provides DesignTemplateInput(card, snapshot)) {
      design.Render(modifier)
    }
  }
}
