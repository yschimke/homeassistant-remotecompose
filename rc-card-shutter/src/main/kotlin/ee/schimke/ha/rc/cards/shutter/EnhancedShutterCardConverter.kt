package ee.schimke.ha.rc.cards.shutter

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.components.HaAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Converter for the HACS `custom:enhanced-shutter-card`.
 *
 * Minimal port: renders per-entity rows with a window/shutter
 * visualisation and open / stop / close buttons. Full feature set of
 * the upstream card (image-based slats & backgrounds, 3D tilt,
 * presets, per-entity resizing, hide-button states) is deliberately
 * deferred — this module exists to prove the custom-card extension
 * seam and give a working shutter out of the box.
 *
 * Register via [CardRegistry.register]:
 *
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

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val title = card.raw["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val cardNamePosition = card.raw["name_position"]?.jsonPrimitive?.content
        val entries = entriesOf(card).map { raw -> toEntry(raw, snapshot, cardNamePosition) }

        val data = HaShutterCardData(
            title = title?.rs,
            entries = entries,
        )
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
            else -> el.jsonPrimitive.content.let { id ->
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
        val name = raw["name"]?.jsonPrimitive?.content
            ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
            ?: entityId
            ?: "(no entity)"
        val namePosition = raw["name_position"]?.jsonPrimitive?.content ?: cardNamePosition
        val closedFraction = closedFractionOf(entity)
        val positionPct = ((1f - closedFraction) * 100).toInt().coerceIn(0, 100)
        val stateLabel = stateLabelFor(entity, positionPct)

        return HaShutterEntryData(
            name = name.rs,
            closedFraction = closedFraction,
            stateLabel = stateLabel.rs,
            showNameOnTop = namePosition != "none",
            openAction = entityId?.let { callService("open_cover", it) } ?: HaAction.None,
            closeAction = entityId?.let { callService("close_cover", it) } ?: HaAction.None,
            stopAction = entityId?.let { callService("stop_cover", it) } ?: HaAction.None,
        )
    }

    private fun callService(service: String, entityId: String): HaAction =
        HaAction.CallService(domain = "cover", service = service, entityId = entityId)

    /** 0f = fully open, 1f = fully closed. */
    private fun closedFractionOf(entity: EntityState?): Float {
        if (entity == null) return 0.5f
        val pos = entity.attributes["current_position"]?.jsonPrimitive?.content?.toFloatOrNull()
        if (pos != null) return (1f - pos / 100f).coerceIn(0f, 1f)
        return when (entity.state) {
            "open", "opening" -> 0f
            "closed", "closing" -> 1f
            else -> 0.5f
        }
    }

    private fun stateLabelFor(entity: EntityState?, positionPct: Int): String {
        if (entity == null) return "Unavailable"
        val state = entity.state
        if (state == "unavailable" || state == "unknown") return "Unavailable"
        val hasPosition = entity.attributes["current_position"] != null
        return if (hasPosition) "$positionPct% open"
        else state.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
    }

    companion object {
        const val CARD_TYPE: String = "custom:enhanced-shutter-card"
    }
}

/**
 * Convenience: `defaultRegistry()` from `:rc-converter` plus this
 * module's converter. Consumers that want the built-in cards and the
 * shutter can use this one call instead of registering by hand.
 */
fun CardRegistry.withEnhancedShutter(): CardRegistry = apply {
    register(EnhancedShutterCardConverter())
}
