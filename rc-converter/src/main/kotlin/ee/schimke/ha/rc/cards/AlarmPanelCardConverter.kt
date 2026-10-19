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
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardEntityIds
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
 * disarmed/armed status, a row of action buttons and the numeric keypad. As in Home Assistant's
 * card, the row is the ARM buttons of the `states:` config (defaulting to away/home) while the
 * entity is `disarmed`, and a single DISARM button in every other state ([alarmButtonServices]).
 *
 * Which buttons show is baked, so the card follows its entity by document re-encode
 * ([dataSignature], live-card-updates.md path 2) and, on the design path, by the `disarm_row`
 * property, which picks a template captured with that row.
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
   * The button row is baked from the entity's state (ARM buttons while disarmed, DISARM otherwise),
   * so arming or disarming must rebuild the document. The status hero and title are baked from the
   * same entity, and a re-encode card takes no binding push, so the whole entity is folded in.
   */
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    cardDataSignature(cardEntityIds(card), snapshot)

  /**
   * An alarm panel renders from a card design: its status variants already switch in-document on
   * `<slot>.state_int`, so only the button row (the configured `states`, or DISARM while the entity
   * isn't disarmed: `disarm_row`) and the keypad (`show_keypad`) are structure. The code length
   * only shapes the actions, which [designAction] rebuilds locally.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!TEMPLATE_CONFIG_KEYS.containsAll(card.raw.keys)) return null
    val entityId = card.raw["entity"]?.jsonPrimitive?.contentOrNull ?: return null
    if (CardDesignSlots.domainOf(entityId) != DOMAIN) return null
    val entity = snapshot.states[entityId] ?: return null
    val states = armStates(card)
    if (states.any { ',' in it || ';' in it }) return null
    // A launcher widget accumulates a known-length PIN in the document; that length is structure.
    val pinLength =
      codeLengthFor(card, entity, alarmButtonServices(card, entity).first())?.takeIf { it in 1..9 }
        ?: 0
    return DesignProperties.build {
      string(STATES, states.joinToString(","))
      enum(SHOW_KEYPAD, if (showKeypad(card)) 1 else 0)
      enum(PIN_LENGTH, pinLength)
      // Only while the row is DISARM, so a disarmed panel keeps the design key it always had.
      if (offersDisarm(entity)) enum(DISARM_ROW, 1)
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
            properties[PIN_LENGTH]?.toIntOrNull()?.takeIf { it > 0 }?.let { put("code_length", it) }
          },
      )
    // Any armed state draws the DISARM row; the status itself is the pushed `<slot>.state_int`.
    val placeholderState = if (properties[DISARM_ROW] == "1") "armed_away" else "disarmed"
    val entity = CardDesignSlots.templateEntity(slot, placeholderState = placeholderState)
    return DesignTemplateInput(card, HaSnapshot(states = mapOf(slot to entity)))
  }

  /**
   * Which ARM / DISARM button or keypad key a template tap pressed, rebuilt from the buttons this
   * card's own config shows for its real entity's current state ([alarmButtonServices]) and code
   * length; keys only while the card shows its keypad. Anything else gets the slot's default tap.
   */
  override fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: HaAction,
  ): HaAction? {
    if (slot != 0) return null
    val entityId = card.raw["entity"]?.jsonPrimitive?.contentOrNull ?: return null
    val entity = snapshot?.states?.get(entityId)
    val services = alarmButtonServices(card, entity)
    return when (templateAction) {
      is HaAction.AlarmIntent ->
        templateAction.service
          .takeIf { it in services }
          ?.let { HaAction.AlarmIntent(entityId, it, codeLengthFor(card, entity, it)) }
      // A PIN typed into a launcher widget: the digits arrive with the tap, the entity is ours.
      is HaAction.AlarmPin ->
        HaAction.AlarmPin(entityId, "").takeIf {
          showKeypad(card) && (codeLengthFor(card, entity, services.first()) ?: 0) in 1..9
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
    val services = alarmButtonServices(card, entity)
    val codeLength = codeLengthFor(card, entity, services.first())
    val actions =
      entityId
        ?.let { id ->
          services.map { service ->
            HaAlarmAction(
              label = actionLabel(service),
              accent = armAccent(service),
              tapAction =
                HaAction.AlarmIntent(
                  entityId = id,
                  service = service,
                  codeLength = codeLengthFor(card, entity, service),
                ),
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
private const val PIN_LENGTH = "pin_length"
private const val DISARM_ROW = "disarm_row"
private const val DISARM = "disarm"
private val TEMPLATE_CONFIG_KEYS =
  setOf("type", "entity", "name", "states", "show_keypad", "code_length")
private val KEYPAD_KEYS = (0..9).map { it.toString() }.toSet() + setOf("backspace", "clear")

private fun armStates(card: CardConfig): List<String> =
  (card.raw["states"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
    ?: listOf("arm_away", "arm_home")

/**
 * Whether the card offers DISARM instead of its ARM buttons: Home Assistant's alarm panel card
 * shows the configured `states` only while the entity is `disarmed`, and DISARM in every other
 * state (armed_*, pending, arming, disarming, triggered, unavailable, unknown). A missing entity
 * keeps the ARM row; HA shows a warning there instead of a panel.
 */
private fun offersDisarm(entity: EntityState?): Boolean =
  entity != null && entity.state != "disarmed"

/** The services the card's button row offers for [entity], in order (see [offersDisarm]). */
private fun alarmButtonServices(card: CardConfig, entity: EntityState?): List<String> =
  if (offersDisarm(entity)) listOf(DISARM) else armStates(card)

/** A button's label: `DISARM`, or `ARM AWAY` for `arm_away`. */
private fun actionLabel(service: String): String =
  if (service == DISARM) "DISARM"
  else "ARM " + service.removePrefix("arm_").replace('_', ' ').uppercase()

private fun showKeypad(card: CardConfig): Boolean =
  card.raw["show_keypad"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

/**
 * The code length the action for [service] carries: 0 when no code is needed (arming an entity that
 * reports `code_arm_required: false`, or disarming one whose `code_format` is null, i.e. it has no
 * code at all; `code_arm_required` doesn't apply to disarming), else the dashboard's `code_length:`
 * (HA doesn't surface a structured length; attribute-level hints are strings like "number" we don't
 * parse), else unknown.
 */
private fun codeLengthFor(card: CardConfig, entity: EntityState?, service: String): Int? {
  val codeRequired =
    if (service == DISARM) {
      val attributes = entity?.attributes
      attributes == null ||
        "code_format" !in attributes ||
        (attributes["code_format"] as? JsonPrimitive)?.contentOrNull != null
    } else {
      entity?.attributes?.get("code_arm_required")?.jsonPrimitive?.booleanOrNull ?: true
    }
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
