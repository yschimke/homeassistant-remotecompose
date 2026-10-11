package ee.schimke.ha.rc.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.AlarmStateInt
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.alarmStateIntFromRaw
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaAlarmAction
import ee.schimke.ha.rc.components.HaAlarmPanelData
import ee.schimke.ha.rc.components.HaAlarmStatus
import ee.schimke.ha.rc.components.RemoteHaAlarmPanel
import ee.schimke.ha.rc.components.RemoteHaAlarmPanelWide
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `alarm-panel` card. Maps `alarm_control_panel.*` entity state into a panel showing the
 * disarmed/armed status, ARM action buttons (driven by `states:` config — defaults to away/home),
 * and the numeric keypad.
 *
 * Each ARM/DISARM button emits an [HaAction.AlarmIntent] (rather than a direct call-service); each
 * keypad press emits an [HaAction.AlarmKey]. The host's `AlarmKeypadCoordinator` buffers keys and
 * pairs them with the most recent intent before firing `alarm_control_panel.alarm_*` with `code:`.
 * When the entity reports `code_arm_required: false` we forward `codeLength = 0` so the coordinator
 * skips buffering and dispatches immediately.
 */
class AlarmPanelCardConverter : CardConverter {
  override val cardType: String = CardTypes.ALARM_PANEL

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 380

  /**
   * An alarm panel renders from a card design: its status variants already switch in-document on
   * `<slot>.state_int`, so only the ARM buttons (`states`) and the keypad (`show_keypad`) are
   * structure. The code length only shapes the actions, which [designAction] rebuilds locally.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!TEMPLATE_CONFIG_KEYS.containsAll(card.raw.keys)) return null
    val entityId = card.raw["entity"]?.jsonPrimitive?.contentOrNull ?: return null
    if (CardDesignSlots.domainOf(entityId) != DOMAIN) return null
    if (entityId !in snapshot.states) return null
    val states = armStates(card)
    if (states.any { ',' in it || ';' in it }) return null
    return DesignProperties.build {
      string(STATES, states.joinToString(","))
      enum(SHOW_KEYPAD, if (showKeypad(card)) 1 else 0)
    }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    val states = properties[STATES]?.split(',')?.filter { it.isNotEmpty() } ?: return null
    val slot = CardDesignSlots.slotId(DOMAIN, 0)
    val card =
      CardConfig(
        type = cardType,
        raw =
          buildJsonObject {
            put("type", cardType)
            put("entity", slot)
            putJsonArray("states") { states.forEach { add(it) } }
            if (properties[SHOW_KEYPAD] == "0") put("show_keypad", false)
          },
      )
    val entity = CardDesignSlots.templateEntity(slot, placeholderState = "disarmed")
    return DesignTemplateInput(card, HaSnapshot(states = mapOf(slot to entity)))
  }

  /**
   * Which ARM button or keypad key a template tap pressed, rebuilt from this card's own `states`
   * and code length against its real entity; keys only while the card shows its keypad. Anything
   * else gets the slot's default tap.
   */
  override fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: HaAction,
  ): HaAction? {
    if (slot != 0) return null
    val entityId = card.raw["entity"]?.jsonPrimitive?.contentOrNull ?: return null
    return when (templateAction) {
      is HaAction.AlarmIntent ->
        templateAction.service
          .takeIf { it in armStates(card) }
          ?.let {
            HaAction.AlarmIntent(entityId, it, codeLengthFor(card, snapshot?.states?.get(entityId)))
          }
      is HaAction.AlarmKey ->
        templateAction.key
          .takeIf { showKeypad(card) && it in KEYPAD_KEYS }
          ?.let { HaAction.AlarmKey(entityId, it) }
      else -> null
    }
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val title =
      card.raw["name"]?.jsonPrimitive?.content
        ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
        ?: entityId
        ?: "Alarm"
    val state = entity?.state ?: "unknown"
    val statesArr = armStates(card)
    val codeLength = codeLengthFor(card, entity)
    val actions =
      entityId
        ?.let { id ->
          statesArr.map { suffix ->
            val label = suffix.removePrefix("arm_").replace('_', ' ').uppercase()
            HaAlarmAction(
              label = "ARM $label",
              accent = armAccent(suffix),
              tapAction =
                HaAction.AlarmIntent(entityId = id, service = suffix, codeLength = codeLength),
            )
          }
        }
        .orEmpty()

    val data =
      HaAlarmPanelData(
        entityId = entityId,
        title = title,
        initialStateInt = alarmStateIntFromRaw(state),
        statuses = AlarmStatuses,
        actions = actions,
        showKeypad = showKeypad(card),
        codeLength = codeLength,
      )

    // Single-gate Wide↔Full ladder, mirroring the arc-dial family
    // (see RenderArcDial / #355): the shield + state hero (and its
    // live state binding) is kept at every size; narrow cells drop
    // the ARM buttons + keypad to the Wide chip, wide cells get the
    // full keypad card.
    when (LocalCardSizeMode.current) {
      CardSizeMode.Wrap -> RemoteHaAlarmPanel(data, modifier)
      CardSizeMode.Fixed ->
        RemoteSizeBreakpoint(
          thresholdsDp = intArrayOf(AlarmFullMinDp),
          modifier = modifier.fillMaxSize(),
        ) { tier ->
          when (tier) {
            0 -> RemoteHaAlarmPanelWide(data, RemoteModifier.fillMaxSize())
            else -> RemoteHaAlarmPanel(data, RemoteModifier.fillMaxSize(), fillHeight = true)
          }
        }
    }
  }
}

private const val DOMAIN = "alarm_control_panel"
private const val STATES = "states"
private const val SHOW_KEYPAD = "show_keypad"
private val TEMPLATE_CONFIG_KEYS =
  setOf("type", "entity", "name", "states", "show_keypad", "code_length")
private val KEYPAD_KEYS = (0..9).map { it.toString() }.toSet() + setOf("backspace", "clear")

private fun armStates(card: CardConfig): List<String> =
  (card.raw["states"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
    ?: listOf("arm_away", "arm_home")

private fun showKeypad(card: CardConfig): Boolean =
  card.raw["show_keypad"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

/**
 * The code length the alarm actions carry: 0 when the entity reports `code_arm_required: false`,
 * else the dashboard's `code_length:` (HA doesn't surface a structured length; attribute-level
 * hints are strings like "number" we don't parse), else unknown.
 */
private fun codeLengthFor(card: CardConfig, entity: EntityState?): Int? {
  val codeRequired =
    entity?.attributes?.get("code_arm_required")?.jsonPrimitive?.booleanOrNull ?: true
  val configuredLen = card.raw["code_length"]?.jsonPrimitive?.intOrNull
  return when {
    !codeRequired -> 0
    configuredLen != null && configuredLen > 0 -> configuredLen
    else -> null
  }
}

// Below this width the keypad's three 48 dp columns can't sit beside
// the chrome padding, so we drop to the Wide shield+state chip (Wear
// and the smaller launcher cell land here); at or above it the full
// keypad card renders. Set above the 200 dp Wear width so both Wear
// containers stay on the Wide chip.
private const val AlarmFullMinDp = 250

private val AccentArmed = Color(0xFF43A047)
private val AccentPending = Color(0xFFFFA000)
private val AccentTriggered = Color(0xFFE53935)
private val AccentNeutral = Color(0xFF757575)

private fun status(key: Int, label: String, accent: Color, icon: ImageVector): HaAlarmStatus =
  HaAlarmStatus(stateKey = key, label = label, accent = accent, icon = icon)

/**
 * One [HaAlarmStatus] per wire key in [AlarmStateInt]. The list is the complete set of variants the
 * alarm-panel chrome can flip through at playback — the host pushes any of these int keys and the
 * `RemoteStateLayout` swaps to the matching variant without a re-encode. Order mirrors
 * [AlarmStateInt.All] so keys stay aligned with the wire contract.
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

private fun armAccent(suffix: String): Color =
  when (suffix) {
    "arm_away" -> Color(0xFF1565C0)
    "arm_home" -> Color(0xFF00838F)
    "arm_night" -> Color(0xFF6A1B9A)
    "disarm" -> Color(0xFFE53935)
    else -> Color(0xFF1565C0)
  }
