package ee.schimke.ha.rc.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
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
import ee.schimke.ha.rc.components.HaAlarmAction
import ee.schimke.ha.rc.components.HaAlarmPanelData
import ee.schimke.ha.rc.components.RemoteHaAlarmPanel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * `alarm-panel` card. Maps `alarm_control_panel.*` entity state into a
 * panel showing the disarmed/armed status, ARM action buttons (driven
 * by `states:` config — defaults to away/home), and a static numeric
 * keypad. Tapping an ARM action fires the corresponding service.
 */
class AlarmPanelCardConverter : CardConverter {
    override val cardType: String = CardTypes.ALARM_PANEL

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 380

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }
        val title = card.raw["name"]?.jsonPrimitive?.content
            ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
            ?: entityId
            ?: "Alarm"
        val state = entity?.state ?: "unknown"
        val statesArr = (card.raw["states"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: listOf("arm_away", "arm_home")
        val actions = entityId?.let { id ->
            statesArr.map { suffix ->
                val label = suffix.removePrefix("arm_").replace('_', ' ').uppercase()
                HaAlarmAction(
                    label = "ARM $label".rs,
                    accent = armAccent(suffix),
                    tapAction = HaAction.CallService(
                        domain = "alarm_control_panel",
                        service = "alarm_$suffix",
                        entityId = id,
                        serviceData = JsonObject(emptyMap()),
                    ),
                )
            }
        }.orEmpty()

        RemoteHaAlarmPanel(
            HaAlarmPanelData(
                title = title.rs,
                state = formatState(state).rs,
                accent = stateAccent(state),
                statusIcon = stateIcon(state),
                actions = actions,
                showKeypad = card.raw["show_keypad"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            ),
            modifier = modifier,
        )
    }
}

private fun stateAccent(state: String): Color = when (state) {
    "armed_away", "armed_home", "armed_night", "armed_vacation", "armed_custom_bypass" ->
        Color(0xFF43A047)
    "disarmed" -> Color(0xFF43A047)
    "pending", "arming" -> Color(0xFFFFA000)
    "triggered" -> Color(0xFFE53935)
    else -> Color(0xFF757575)
}

private fun armAccent(suffix: String): Color = when (suffix) {
    "arm_away" -> Color(0xFF1565C0)
    "arm_home" -> Color(0xFF00838F)
    "arm_night" -> Color(0xFF6A1B9A)
    "disarm" -> Color(0xFFE53935)
    else -> Color(0xFF1565C0)
}

private fun stateIcon(state: String): ImageVector = when (state) {
    "disarmed" -> Icons.Filled.LockOpen
    "triggered" -> Icons.Filled.Warning
    "armed_away", "armed_home", "armed_night", "armed_vacation", "armed_custom_bypass" -> Icons.Filled.Shield
    else -> Icons.Filled.Lock
}

private fun formatState(s: String): String =
    s.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
