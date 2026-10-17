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
    val entryCount = entriesOf(card).size.coerceAtLeast(1)
    // ~140dp per shutter row (viz + name + state + gaps); plus title.
    return (if (hasTitle) 28 else 0) + 20 + entryCount * 140
  }

  // Shutter height, button states and the state line are baked from the cover's state, so the
  // card re-encodes when it changes (live-card-updates.md, path 2).
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    cardDataSignature(cardEntityIds(card), snapshot)

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val title = card.raw["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    val cardNamePosition = card.raw["name_position"]?.jsonPrimitive?.content
    val entries = entriesOf(card).map { raw -> toEntry(raw, snapshot, cardNamePosition) }

    val data = HaShutterCardData(title = title?.rs, entries = entries)
    RemoteHaShutter(data, modifier)
  }

  private fun entriesOf(card: CardConfig): List<JsonObject> {
    // Two accepted shapes:
    //   (a) entities: [ { entity: "...", ... }, ... ]
    //   (b) entity: "cover.x"                — single-entity form
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
  ): HaShutterEntryData {
    val entityId = raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val name =
      raw["name"]?.jsonPrimitive?.content
        ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
        ?: entityId
        ?: "(no entity)"
    val namePosition = raw["name_position"]?.jsonPrimitive?.content ?: cardNamePosition
    val garage = isGarageLike(entity)
    val closedFraction = coverClosedFraction(entity, unknownFraction = if (garage) 1f else 0.5f)

    return HaShutterEntryData(
      name = name.rs,
      closedFraction = closedFraction,
      stateLabel = coverStateLabel(entity).rs,
      showNameOnTop = namePosition != "none",
      openAction = entityId?.let { callService("open_cover", it) } ?: HaAction.None,
      closeAction = entityId?.let { callService("close_cover", it) } ?: HaAction.None,
      stopAction = entityId?.let { callService("stop_cover", it) } ?: HaAction.None,
      controls = coverControls(entity, CoverHideStates.fromEntry(raw)),
      motion = coverMotion(entity),
      garage = garage,
    )
  }

  private fun callService(service: String, entityId: String): HaAction =
    HaAction.CallService(domain = "cover", service = service, entityId = entityId)

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
