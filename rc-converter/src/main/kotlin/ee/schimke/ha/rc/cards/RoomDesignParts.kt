@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.components.RemoteHaArea
import ee.schimke.ha.rc.components.RemoteHaMediaControl
import ee.schimke.ha.rc.components.RemoteHaMediaControlWide
import ee.schimke.ha.rc.components.RemoteHaSummaryLine
import ee.schimke.ha.rc.components.cardText
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The parts the room cards' designs arrange (card-designs/ui-builder/designs/`area.uid` and
 * `media-control.uid`): each draws one form of its card for the card being drawn
 * ([LocalCardInput]); which form a surface gets is the design's `card-size-mode` and `size-tiers`.
 * The card design renderer draws them through [components]; ha-cards-catalog's `HaAreaCard*` and
 * `HaMediaControlCard*` wrappers put the same calls on UI Builder's shelf.
 */
object RoomDesignParts {
  private val area = AreaCardConverter()

  /** The card being drawn, or [placeholder] in UI Builder's canvas. */
  @Composable
  private fun input(type: String, vararg fields: Pair<String, String>): DesignTemplateInput =
    LocalCardInput.current
      ?: DesignTemplateInput(
        CardConfig(
          type,
          JsonObject(
            mapOf("type" to JsonPrimitive(type)) +
              fields.associate { (k, v) -> k to JsonPrimitive(v) }
          ),
        ),
        HaSnapshot(),
      )

  /** `ha-cards/area-card-full`: the area's name, readings and action chips. */
  @Composable
  fun AreaFull(modifier: RemoteModifier) {
    val (card, snapshot) = input("area", "area" to "living_room", "name" to "Living room")
    RemoteHaArea(area.areaData(card, snapshot), modifier = modifier)
  }

  /**
   * `ha-cards/area-card-summary`: the area's name over its first reading, for a Wear Small slot.
   */
  @Composable
  fun AreaSummary() {
    val (card, snapshot) = input("area", "area" to "living_room", "name" to "Living room")
    val data = area.areaData(card, snapshot)
    RemoteHaSummaryLine(
      if (data.stats.isEmpty()) "Area".rs else cardText("name", data.name),
      data.stats.firstOrNull()?.label ?: cardText("name", data.name),
    )
  }

  /**
   * `ha-cards/media-control-card-full`: the art, titles, transport row and seek bar; [fill] fills a
   * fixed cell's height.
   */
  @Composable
  fun MediaFull(fill: Boolean, modifier: RemoteModifier) {
    val (card, snapshot) = input("media-control", "entity" to "media_player.living_room")
    RemoteHaMediaControl(mediaData(card, snapshot), modifier, fillHeight = fill)
  }

  /** `ha-cards/media-control-card-wide`: the art and play/pause hero, for a narrow cell. */
  @Composable
  fun MediaWide(modifier: RemoteModifier) {
    val (card, snapshot) = input("media-control", "entity" to "media_player.living_room")
    RemoteHaMediaControlWide(mediaData(card, snapshot), modifier)
  }

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/area-card-full" to { _, modifier -> AreaFull(modifier) },
      "ha-cards/area-card-summary" to { _, _ -> AreaSummary() },
      "ha-cards/media-control-card-full" to
        { node, modifier ->
          MediaFull(node.bool("fill"), modifier)
        },
      "ha-cards/media-control-card-wide" to { _, modifier -> MediaWide(modifier) },
    )
}
