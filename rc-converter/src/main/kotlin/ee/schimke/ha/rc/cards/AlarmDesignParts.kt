@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.components.RemoteHaAlarmPanel
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The parts the alarm panel card's design arranges (card-designs/ui-builder/designs/
 * `alarm-panel.uid`): each draws one form of the panel being drawn ([LocalCardInput]); which form a
 * surface gets is the design's `card-size-mode` and `size-tiers`. The card design renderer draws
 * them through [components]; ha-cards-catalog's `HaAlarmPanelCard*` wrappers put the same calls on
 * UI Builder's shelf.
 */
object AlarmDesignParts {
  private val converter = AlarmPanelCardConverter()

  /** The card being drawn, or a panel for `alarm_control_panel.home` in UI Builder's canvas. */
  @Composable
  private fun input(): DesignTemplateInput =
    LocalCardInput.current
      ?: DesignTemplateInput(
        CardConfig(
          "alarm-panel",
          JsonObject(
            mapOf(
              "type" to JsonPrimitive("alarm-panel"),
              "entity" to JsonPrimitive("alarm_control_panel.home"),
            )
          ),
        ),
        HaSnapshot(),
      )

  /** `ha-cards/alarm-panel-card-full`: the status, the ARM buttons and the keypad. */
  @Composable
  fun Full(modifier: RemoteModifier) {
    val (card, snapshot) = input()
    RemoteHaAlarmPanel(converter.alarmData(card, snapshot), modifier)
  }

  /** `ha-cards/alarm-panel-card-narrow`: a fixed cell narrower than the full card. */
  @Composable
  fun Narrow() {
    val (card, snapshot) = input()
    converter.NarrowTier(converter.alarmData(card, snapshot))
  }

  /** `ha-cards/alarm-panel-card-wide`: a fixed cell as wide as the full card. */
  @Composable
  fun Wide() {
    val (card, snapshot) = input()
    converter.FullTier(converter.alarmData(card, snapshot))
  }

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/alarm-panel-card-full" to { _, modifier -> Full(modifier) },
      "ha-cards/alarm-panel-card-narrow" to { _, _ -> Narrow() },
      "ha-cards/alarm-panel-card-wide" to { _, _ -> Wide() },
    )
}
