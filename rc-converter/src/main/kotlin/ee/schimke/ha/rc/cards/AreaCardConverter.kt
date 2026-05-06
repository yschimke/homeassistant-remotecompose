package ee.schimke.ha.rc.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Window
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaAreaAction
import ee.schimke.ha.rc.components.HaAreaCardData
import ee.schimke.ha.rc.components.HaAreaStat
import ee.schimke.ha.rc.components.RemoteHaArea
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `area` card. The card config carries an `area:` id that we can't
 * resolve without a registry channel. We accept an optional
 * `entities:` list (HA also accepts that as an override) — temperature
 * / humidity sensors light the stat row, light/cover/fan entities
 * become action chips. Tap fires the entity's domain toggle.
 */
class AreaCardConverter : CardConverter {
    override val cardType: String = CardTypes.AREA

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 140

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val name = card.raw["name"]?.jsonPrimitive?.content
            ?: card.raw["area"]?.jsonPrimitive?.content
                ?.replace('_', ' ')
                ?.replaceFirstChar { it.uppercaseChar() }
            ?: "Area"

        val explicitEntities = card.raw["entities"] as? kotlinx.serialization.json.JsonArray
        val entityIds = explicitEntities?.mapNotNull {
            when (it) {
                is kotlinx.serialization.json.JsonPrimitive -> it.content
                is JsonObject -> it["entity"]?.jsonPrimitive?.content
                else -> null
            }
        } ?: emptyList()

        val stats = entityIds.mapNotNull { id ->
            val entity = snapshot.states[id] ?: return@mapNotNull null
            val deviceClass = entity.attributes["device_class"]?.jsonPrimitive?.content
            val unit = entity.attributes["unit_of_measurement"]?.jsonPrimitive?.content
            when (deviceClass) {
                "temperature" -> HaAreaStat(Icons.Filled.Thermostat,
                    "${entity.state}${unit ?: " °C"}".rs)
                "humidity", "moisture" -> HaAreaStat(Icons.Filled.WaterDrop,
                    "${entity.state}${unit ?: " %"}".rs)
                else -> null
            }
        }

        val actions = entityIds.mapNotNull { id ->
            val entity = snapshot.states[id] ?: return@mapNotNull null
            val domain = id.substringBefore('.')
            val (icon, accent) = when (domain) {
                "light" -> Icons.Filled.Lightbulb to Color(0xFFFFBE3E)
                "switch", "input_boolean" -> Icons.Filled.PowerSettingsNew to Color(0xFF2196F3)
                "cover" -> Icons.Filled.Window to Color(0xFF00897B)
                "fan" -> Icons.Filled.Air to Color(0xFF00BFA5)
                else -> return@mapNotNull null
            }
            HaAreaAction(
                icon = icon as ImageVector,
                accent = accent,
                isActive = entity.state == "on" || entity.state == "open",
                tapAction = HaAction.Toggle(id),
            )
        }

        RemoteHaArea(
            HaAreaCardData(
                name = name.rs,
                stats = stats,
                actions = actions,
            ),
            modifier = modifier,
        )
    }
}
