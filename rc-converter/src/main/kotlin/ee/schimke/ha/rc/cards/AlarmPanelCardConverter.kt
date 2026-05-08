package ee.schimke.ha.rc.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import ee.schimke.ha.model.AlarmStateInt
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.alarmStateIntFromRaw
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaAlarmAction
import ee.schimke.ha.rc.components.HaAlarmPanelData
import ee.schimke.ha.rc.components.HaAlarmStatus
import ee.schimke.ha.rc.components.RemoteHaAlarmPanel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `alarm-panel` card. Maps `alarm_control_panel.*` entity state into a
 * panel showing the disarmed/armed status, ARM action buttons (driven
 * by `states:` config — defaults to away/home), and the numeric
 * keypad.
 *
 * Each ARM/DISARM button emits an [HaAction.AlarmIntent] (rather than
 * a direct call-service); each keypad press emits an
 * [HaAction.AlarmKey]. The host's `AlarmKeypadCoordinator` buffers
 * keys and pairs them with the most recent intent before firing
 * `alarm_control_panel.alarm_*` with `code:`. When the entity reports
 * `code_arm_required: false` we forward `codeLength = 0` so the
 * coordinator skips buffering and dispatches immediately.
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
        val codeRequired = entity?.attributes?.get("code_arm_required")
            ?.jsonPrimitive?.booleanOrNull ?: true
        // HA doesn't surface a structured length, so let the dashboard
        // YAML pin one (`code_length: 4`); attribute-level hints are
        // strings like "number" / "^\\d{4}$" that we don't parse.
        val configuredLen = card.raw["code_length"]?.jsonPrimitive?.intOrNull
        val codeLength: Int? = when {
            !codeRequired -> 0
            configuredLen != null && configuredLen > 0 -> configuredLen
            else -> null
        }
        val actions =
            entityId
                ?.let { id ->
                    statesArr.map { suffix ->
                        val label = suffix.removePrefix("arm_").replace('_', ' ').uppercase()
                        HaAlarmAction(
                            label = "ARM $label",
                            accent = armAccent(suffix),
                            tapAction = HaAction.AlarmIntent(
                                entityId = id,
                                service = suffix,
                                codeLength = codeLength,
                            ),
                        )
                    }
                }
                .orEmpty()

        RemoteHaAlarmPanel(
            HaAlarmPanelData(
                entityId = entityId,
                title = title,
                initialStateInt = alarmStateIntFromRaw(state),
                statuses = AlarmStatuses,
                actions = actions,
                showKeypad =
                    card.raw["show_keypad"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            ),
            modifier = modifier,
        )
    }
}

private val AccentArmed = Color(0xFF43A047)
private val AccentPending = Color(0xFFFFA000)
private val AccentTriggered = Color(0xFFE53935)
private val AccentNeutral = Color(0xFF757575)

private fun status(key: Int, label: String, accent: Color, icon: ImageVector): HaAlarmStatus =
    HaAlarmStatus(stateKey = key, label = label, accent = accent, icon = icon)

/**
 * One [HaAlarmStatus] per wire key in [AlarmStateInt]. The list is the
 * complete set of variants the alarm-panel chrome can flip through at
 * playback — the host pushes any of these int keys and the
 * `RemoteStateLayout` swaps to the matching variant without a
 * re-encode. Order mirrors [AlarmStateInt.All] so keys stay aligned
 * with the wire contract.
 */
private val AlarmStatuses: List<HaAlarmStatus> =
    listOf(
        status(AlarmStateInt.Disarmed, "Disarmed", AccentArmed, Icons.Filled.LockOpen),
        status(AlarmStateInt.ArmedHome, "Armed home", AccentArmed, Icons.Filled.Shield),
        status(AlarmStateInt.ArmedAway, "Armed away", AccentArmed, Icons.Filled.Shield),
        status(AlarmStateInt.ArmedNight, "Armed night", AccentArmed, Icons.Filled.Shield),
        status(AlarmStateInt.ArmedVacation, "Armed vacation", AccentArmed, Icons.Filled.Shield),
        status(
            AlarmStateInt.ArmedCustomBypass,
            "Armed custom bypass",
            AccentArmed,
            Icons.Filled.Shield,
        ),
        status(AlarmStateInt.Triggered, "Triggered", AccentTriggered, Icons.Filled.Warning),
        status(AlarmStateInt.Pending, "Pending", AccentPending, Icons.Filled.Lock),
        status(AlarmStateInt.Arming, "Arming", AccentPending, Icons.Filled.Lock),
        status(AlarmStateInt.Disarming, "Disarming", AccentPending, Icons.Filled.Lock),
        status(AlarmStateInt.Unavailable, "Unavailable", AccentNeutral, Icons.Filled.Lock),
        status(AlarmStateInt.Unknown, "Unknown", AccentNeutral, Icons.Filled.Lock),
    )

private fun armAccent(suffix: String): Color = when (suffix) {
    "arm_away" -> Color(0xFF1565C0)
    "arm_home" -> Color(0xFF00838F)
    "arm_night" -> Color(0xFF6A1B9A)
    "disarm" -> Color(0xFFE53935)
    else -> Color(0xFF1565C0)
}
