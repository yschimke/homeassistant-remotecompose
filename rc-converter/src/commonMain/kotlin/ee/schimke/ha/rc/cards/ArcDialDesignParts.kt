@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaIconIdentity
import ee.schimke.ha.rc.LocalInCardContainer
import ee.schimke.ha.rc.components.HaArcDialData
import ee.schimke.ha.rc.components.RemoteHaArcDial
import ee.schimke.ha.rc.components.RemoteHaArcDialWide
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The parts the arc-dial cards' designs arrange (card-designs/ui-builder/designs/`thermostat.uid`,
 * `humidifier.uid` and `light.uid`): each draws one form of the dial for the card being drawn
 * ([LocalCardInput]), whichever of the three it is; which form a surface gets is the design's
 * `card-size-mode` and `size-tiers`. The card design renderer draws them through [components];
 * ha-cards-catalog's `HaArcDialCard*` wrappers put the same calls on UI Builder's shelf.
 */
object ArcDialDesignParts {
  /** The card being drawn, or a thermostat in UI Builder's canvas. */
  @Composable
  private fun input(): DesignTemplateInput =
    LocalCardInput.current
      ?: DesignTemplateInput(
        CardConfig(
          CardTypes.THERMOSTAT,
          JsonObject(
            mapOf(
              "type" to JsonPrimitive(CardTypes.THERMOSTAT),
              "entity" to JsonPrimitive("climate.living_room"),
            )
          ),
        ),
        HaSnapshot(),
      )

  /** [card]'s dial: the light's, the humidifier's or the thermostat's. */
  private fun dialOf(card: CardConfig, snapshot: HaSnapshot): HaArcDialData =
    when (card.type) {
      CardTypes.LIGHT -> lightDial(card, snapshot)
      CardTypes.HUMIDIFIER -> humidifierDial(card, snapshot)
      else -> thermostatDial(card, snapshot)
    }

  /** `ha-cards/arc-dial-card-full`: the full vertical card, dial over steppers, on a dashboard. */
  @Composable
  fun Full(modifier: RemoteModifier) {
    val (card, snapshot) = input()
    RemoteHaArcDial(dialOf(card, snapshot), modifier)
  }

  /**
   * `ha-cards/arc-dial-card-wear`: the arc-left Wide row in a Wear container; [compact] is the
   * Small container's two lines. The entity is named on a watch (card-information-hierarchy.md)
   * unless the card is in a stack or grid, whose container names it; [keepsMode] keeps the mode
   * chip under the name in the Large container, for a dial whose chip says more than its value and
   * accent.
   */
  @Composable
  fun Wear(compact: Boolean, keepsMode: Boolean, modifier: RemoteModifier) {
    val (card, snapshot) = input()
    val showsName = !LocalInCardContainer.current
    RemoteHaArcDialWide(
      dialOf(card, snapshot),
      modifier,
      compact = compact,
      dialDp = if (compact) 56 else WEAR_LARGE_DIAL_DP,
      showName = showsName,
      showMode = !showsName || (keepsMode && !compact),
    )
  }

  /** `ha-cards/arc-dial-card-launcher`: the [ArcDialLauncher] ladder in a launcher cell. */
  @Composable
  fun Launcher(modifier: RemoteModifier) {
    val (card, snapshot) = input()
    ArcDialLauncher(
      dialOf(card, snapshot),
      modifier,
      // The dial domains carry no device class the icon rule reads.
      oneColumnKeepsName = HaIconIdentity.chipKeepsName(card, entity = null),
    )
  }

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/arc-dial-card-full" to { _, modifier -> Full(modifier) },
      "ha-cards/arc-dial-card-wear" to
        { node, modifier ->
          Wear(node.bool("compact"), node.bool("keepsMode"), modifier)
        },
      "ha-cards/arc-dial-card-launcher" to { _, modifier -> Launcher(modifier) },
    )
}
