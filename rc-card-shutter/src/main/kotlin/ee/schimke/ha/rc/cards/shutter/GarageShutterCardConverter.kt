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
 * Converter for `custom:garage-shutter-card`.
 *
 * Mirrors [EnhancedShutterCardConverter] for window shutters but
 * targets garage doors:
 *
 *   - sectional-door visualisation (horizontal aspect, panel grooves),
 *   - direction-of-motion chevron driven by the cover's
 *     `opening` / `closing` state,
 *   - state label normalises HA's `device_class: garage` cover states
 *     to natural English ("Open" / "Closed" / "Opening…").
 *
 * Position semantics match the shutter card so the two cards' fractions
 * can share host-side animation code: `closedFraction` 0..1 where 1 is
 * fully closed. HA's `current_position` is "100 = fully open" so we
 * invert it once at the converter boundary.
 *
 * Register via [CardRegistry.withGarageShutter] (or both at once via
 * [withAllShutters]).
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

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val title = card.raw["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val cardNamePosition = card.raw["name_position"]?.jsonPrimitive?.content
        val entries = entriesOf(card).map { raw -> toEntry(raw, snapshot, cardNamePosition) }

        val data = HaGarageCardData(
            title = title?.rs,
            entries = entries,
        )
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
            else -> el.jsonPrimitive.content.let { id ->
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
        val name = raw["name"]?.jsonPrimitive?.content
            ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
            ?: entityId
            ?: "(no entity)"
        val namePosition = raw["name_position"]?.jsonPrimitive?.content ?: cardNamePosition
        val closedFraction = closedFractionOf(entity)
        val motion = motionOf(entity)
        val stateLabel = stateLabelFor(entity, closedFraction)

        return HaGarageEntryData(
            name = name.rs,
            closedFraction = closedFraction,
            motion = motion,
            stateLabel = stateLabel.rs,
            showNameOnTop = namePosition != "none",
            openAction = entityId?.let { callService("open_cover", it) } ?: HaAction.None,
            closeAction = entityId?.let { callService("close_cover", it) } ?: HaAction.None,
            stopAction = entityId?.let { callService("stop_cover", it) } ?: HaAction.None,
        )
    }

    private fun callService(service: String, entityId: String): HaAction =
        HaAction.CallService(domain = "cover", service = service, entityId = entityId)

    /** 0f = fully open, 1f = fully closed. Mirrors the shutter card. */
    private fun closedFractionOf(entity: EntityState?): Float {
        if (entity == null) return 1f
        val pos = entity.attributes["current_position"]?.jsonPrimitive?.content?.toFloatOrNull()
        if (pos != null) return (1f - pos / 100f).coerceIn(0f, 1f)
        // Many garage-door integrations don't expose current_position;
        // pick a representative value per state so the visualisation
        // still tells the user something. `opening` / `closing` show
        // the door at half — combined with the motion chevron that
        // reads as "in transit", which is the right mental model.
        return when (entity.state) {
            "open" -> 0f
            "opening" -> 0.5f
            "closing" -> 0.5f
            "closed" -> 1f
            else -> 1f
        }
    }

    private fun motionOf(entity: EntityState?): GarageMotion = when (entity?.state) {
        "opening" -> GarageMotion.Opening
        "closing" -> GarageMotion.Closing
        else -> GarageMotion.Idle
    }

    private fun stateLabelFor(entity: EntityState?, closedFraction: Float): String {
        if (entity == null) return "Unavailable"
        val state = entity.state
        if (state == "unavailable" || state == "unknown") return "Unavailable"
        val hasPosition = entity.attributes["current_position"] != null
        if (hasPosition) {
            val pct = ((1f - closedFraction) * 100).toInt().coerceIn(0, 100)
            return when (pct) {
                0 -> "Closed"
                100 -> "Open"
                else -> "$pct% open"
            }
        }
        return when (state) {
            "open" -> "Open"
            "closed" -> "Closed"
            "opening" -> "Opening…"
            "closing" -> "Closing…"
            else -> state.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
        }
    }

    companion object {
        const val CARD_TYPE: String = "custom:garage-shutter-card"
    }
}

/**
 * Convenience: register [GarageShutterCardConverter] on this registry.
 * Mirrors the shape of [withEnhancedShutter] so consumers can chain
 * either or both.
 */
fun CardRegistry.withGarageShutter(): CardRegistry = apply {
    register(GarageShutterCardConverter())
}

/**
 * Convenience: register both shutter-family converters at once. Most
 * dashboards that want the window shutter also want the garage card,
 * and vice-versa, so wire them with one call.
 */
fun CardRegistry.withAllShutters(): CardRegistry = apply {
    register(EnhancedShutterCardConverter())
    register(GarageShutterCardConverter())
}
