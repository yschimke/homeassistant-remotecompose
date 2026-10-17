package ee.schimke.ha.rc.cards.shutter

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardEntityIds
import ee.schimke.ha.rc.components.HaAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
    val entryCount = entriesOf(card).size.coerceAtLeast(1)
    // Per-row: 13dp name + 4dp + 72dp viz + 4dp + 11dp state ≈ 104dp,
    // plus 10dp inter-row gap. Title + outer padding adds ~28dp + 20dp.
    return (if (hasTitle) 28 else 0) + 20 + entryCount * 114
  }

  // Door height, button states and the state line are baked from the cover's state, so the card
  // re-encodes when it changes (live-card-updates.md, path 2).
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    cardDataSignature(cardEntityIds(card), snapshot)

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val title = card.raw["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    val cardNamePosition = card.raw["name_position"]?.jsonPrimitive?.content
    val entries = entriesOf(card).map { raw -> toEntry(raw, snapshot, cardNamePosition) }

    val data = HaGarageCardData(title = title?.rs, entries = entries)
    RemoteHaGarage(data, modifier)
  }

  private fun entriesOf(card: CardConfig): List<JsonObject> {
    val list = card.raw["entities"]?.jsonArray
    if (list != null) return list.normalisedEntries()
    val singleEntity = card.raw["entity"]?.jsonPrimitive?.content
    return if (singleEntity != null) listOf(card.raw) else emptyList()
  }

  private fun JsonArray.normalisedEntries(): List<JsonObject> = mapNotNull { el ->
    when (el) {
      is JsonObject -> el
      else ->
        el.jsonPrimitive.content.let { id ->
          JsonObject(mapOf("entity" to kotlinx.serialization.json.JsonPrimitive(id)))
        }
    }
  }

  private fun toEntry(
    raw: JsonObject,
    snapshot: HaSnapshot,
    cardNamePosition: String?,
  ): HaGarageEntryData {
    val entityId = raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val name =
      raw["name"]?.jsonPrimitive?.content
        ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
        ?: entityId
        ?: "(no entity)"
    val namePosition = raw["name_position"]?.jsonPrimitive?.content ?: cardNamePosition
    // Many garage-door integrations don't expose current_position; an unknown door is drawn
    // closed, and a moving one half-way with the motion arrow.
    val closedFraction = coverClosedFraction(entity, unknownFraction = 1f)

    return HaGarageEntryData(
      name = name.rs,
      closedFraction = closedFraction,
      motion = coverMotion(entity),
      stateLabel = coverStateLabel(entity).rs,
      showNameOnTop = namePosition != "none",
      openAction = entityId?.let { callService("open_cover", it) } ?: HaAction.None,
      closeAction = entityId?.let { callService("close_cover", it) } ?: HaAction.None,
      stopAction = entityId?.let { callService("stop_cover", it) } ?: HaAction.None,
      controls = coverControls(entity),
    )
  }

  private fun callService(service: String, entityId: String): HaAction =
    HaAction.CallService(domain = "cover", service = service, entityId = entityId)

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
