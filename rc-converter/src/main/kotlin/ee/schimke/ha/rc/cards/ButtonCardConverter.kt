package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.toTyped
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LiveBindings
import ee.schimke.ha.rc.components.HaButtonData
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.RemoteHaButton
import ee.schimke.ha.rc.components.RemoteHaToggleButton
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `button` card. Toggleable entities (lights / switches / locks /
 * covers / media-players etc) render through [RemoteHaToggleButton]
 * so tap flips the visual optimistically via an in-document
 * `MutableRemoteBoolean` + `ValueChange`. Read-only entities go
 * through the simpler [RemoteHaButton] (no local state machine).
 */
class ButtonCardConverter : CardConverter {
    override val cardType: String = CardTypes.BUTTON

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }
        val name = card.raw["name"]?.jsonPrimitive?.content
            ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
            ?: entityId ?: "Button"
        val showName = card.raw["show_name"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        val tapCfg = card.raw["tap_action"]?.jsonObject
        val tapAction = if (tapCfg != null) parseHaAction(tapCfg, entityId)
        else defaultTapActionFor(entityId)

        val isOn = LiveBindings.isOn(entity)
        val initiallyOn = entity?.toTyped()?.isActive == true
        val data = HaButtonData(
            name = name.rs,
            icon = HaIconMap.resolve(card.raw["icon"]?.jsonPrimitive?.content, entity),
            accent = HaToggleAccent(
                activeAccent = HaStateColor.activeFor(entity).rc,
                inactiveAccent = HaStateColor.inactiveFor(entity).rc,
                isOn = isOn,
                initiallyOn = initiallyOn,
            ),
            showName = showName,
            tapAction = tapAction,
        )

        if (isOn != null) RemoteHaToggleButton(data, modifier) else RemoteHaButton(data, modifier)
    }
}
