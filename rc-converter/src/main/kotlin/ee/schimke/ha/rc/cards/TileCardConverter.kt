package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.parseHaAction
import ee.schimke.ha.rc.tileIconStyleFor
import ee.schimke.ha.rc.components.RemoteHaTile
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Thin shim from a Lovelace `tile` card config + HA snapshot to the typed
 * [RemoteHaTile] composable. All visual logic lives in `:rc-components`.
 */
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
        val iconOverride = card.raw["icon"]?.jsonPrimitive?.content

        val tapCfg = card.raw["tap_action"]?.jsonObject
        val tapAction = if (tapCfg != null) parseHaAction(tapCfg, entityId)
        else defaultTapActionFor(entityId)

        RemoteHaTile(
            name = name.rs,
            state = formatState(entity).rs,
            icon = HaIconMap.resolve(iconOverride, entity),
            accent = HaStateColor.resolve(entity).rc,
            iconStyle = tileIconStyleFor(entity),
            tapAction = tapAction,
        )
    }
}
