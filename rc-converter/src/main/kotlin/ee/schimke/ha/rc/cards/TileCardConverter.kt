package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LiveBindings
import ee.schimke.ha.rc.components.HaTileData
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.RemoteHaTile
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `tile` card — builds [HaTileData] from config + snapshot. */
class TileCardConverter : CardConverter {
    override val cardType: String = CardTypes.TILE

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot) {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }
        val name = card.raw["name"]?.jsonPrimitive?.content
            ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
            ?: entityId
            ?: "(no entity)"
        val tapCfg = card.raw["tap_action"]?.jsonObject
        val tapAction = if (tapCfg != null) parseHaAction(tapCfg, entityId)
        else defaultTapActionFor(entityId)

        RemoteHaTile(
            HaTileData(
                name = name.rs,
                state = LiveBindings.state(entity, formatState(entity)),
                icon = HaIconMap.resolve(card.raw["icon"]?.jsonPrimitive?.content, entity),
                accent = HaToggleAccent(
                    activeAccent = HaStateColor.activeFor(entity).rc,
                    inactiveAccent = HaStateColor.inactiveFor(entity).rc,
                    isOn = LiveBindings.isOn(entity),
                ),
                tapAction = tapAction,
            ),
        )
    }
}
