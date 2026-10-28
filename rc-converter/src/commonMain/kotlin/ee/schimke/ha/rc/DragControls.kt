package ee.schimke.ha.rc

import ee.schimke.ha.model.CardConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The tile feature types drawn as a slider ([ee.schimke.ha.rc.components.RemoteHaSlider]); a
 * `numeric-input` only in its slider style.
 */
internal val SLIDER_FEATURE_TYPES: Set<String> =
  setOf("light-brightness", "cover-position", "media-player-volume-slider")

/**
 * Whether [card], or a card nested in it, asks for a drag control: a tile feature drawn as a
 * slider.
 *
 * A host that locks controls on a surface brushed by swipes (a launcher widget, the watch's list)
 * locks the cards that have one ([ee.schimke.ha.rc.components.HaControlLockMode.Locked]): a drag
 * control makes the player claim every gesture that starts on the card, so the card is unlocked on
 * purpose, with the same kind of gesture. A card of taps alone keeps acting on a tap, as it always
 * has, and leaves the home screen's swipes to the launcher.
 */
fun cardHasDragControls(card: CardConfig): Boolean = card.raw.hasDragFeature()

private fun JsonElement.hasDragFeature(): Boolean =
  when (this) {
    is JsonObject ->
      (this["features"] as? JsonArray)?.any { it.isSliderFeature() } == true ||
        values.any { it.hasDragFeature() }
    is JsonArray -> any { it.hasDragFeature() }
    else -> false
  }

private fun JsonElement.isSliderFeature(): Boolean {
  val feature = this as? JsonObject ?: return false
  val type = (feature["type"] as? JsonPrimitive)?.contentOrNull
  return type in SLIDER_FEATURE_TYPES ||
    (type == "numeric-input" && (feature["style"] as? JsonPrimitive)?.contentOrNull == "slider")
}
