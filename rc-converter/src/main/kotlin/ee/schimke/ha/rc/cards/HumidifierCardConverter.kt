package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardEntityIds
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaArcDialData
import ee.schimke.ha.rc.components.HaModeChip
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `humidifier` card — humidifier entity as a 270° arc dial.
 *
 * Same shape as the thermostat card but the arc fills with the current humidity %, the target
 * humidity is the supportingLabel, and the mode chip reads "Humidifying" / "Drying" / "Off".
 */
class HumidifierCardConverter : CardConverter {
  override val cardType: String = CardTypes.HUMIDIFIER

  // Baked, non-bindable content (see CardConverter.dataSignature):
  // re-encode when any referenced entity's snapshot data moves.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    cardDataSignature(cardEntityIds(card), snapshot)

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 290

  /** A humidifier renders from a card design; see [DialCard]. */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    dial.properties(card, snapshot)

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    dial.template(properties)

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings =
    dial.bindings(card, snapshot)

  override fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: HaAction,
  ): HaAction? = dial.action(card, snapshot, templateAction)

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    RenderArcDial(humidifierDial(card, snapshot), modifier)
  }

  private val dial =
    DialCard(
      cardType = cardType,
      domain = "humidifier",
      configKeys = setOf("type", "entity", "name"),
      placeholder = { hasTarget ->
        "on" to
          buildMap {
            put("current_humidity", "40")
            if (hasTarget) put("humidity", "50")
          }
      },
      data = ::humidifierDial,
    )
}

/** The humidifier's dial for [card] and [snapshot]: what it draws, and what a template binds. */
internal fun humidifierDial(card: CardConfig, snapshot: HaSnapshot): HaArcDialData {
  val entityId = card.raw["entity"]?.jsonPrimitive?.content
  val entity = entityId?.let { snapshot.states[it] }
  val attrs = entity?.attributes ?: JsonObject(emptyMap())
  val name =
    card.raw["name"]?.jsonPrimitive?.content
      ?: attrs["friendly_name"]?.jsonPrimitive?.content
      ?: entityId
      ?: "Humidifier"
  val current = attrs["current_humidity"]?.jsonPrimitive?.content?.toFloatOrNull()
  val target = attrs["humidity"]?.jsonPrimitive?.content?.toFloatOrNull()
  val min = attrs["min_humidity"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
  val max = attrs["max_humidity"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 100f
  val state = entity?.state ?: "off"
  val action = attrs["action"]?.jsonPrimitive?.content
  val modeChip =
    when {
      state == "off" -> "Off"
      action == "humidifying" -> "Humidifying"
      action == "drying" -> "Drying"
      action == "idle" -> "Idle"
      else -> state.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
    }

  val span = (max - min).coerceAtLeast(0.0001f)
  val valueFraction = ((current ?: target ?: min) - min) / span
  val targetFraction = target?.let { (it - min) / span }

  val centerLabel = (current ?: target)?.let { "${it.toInt()}%" } ?: "—"
  val supportingLabel = target?.let { "↑ ${it.toInt()}%" }

  val (incAction, decAction) = stepperActions(entity?.entityId, target, 5f)

  return HaArcDialData(
    entityId = entity?.entityId,
    name = name,
    valueFraction = valueFraction.coerceIn(0f, 1f),
    targetFraction = targetFraction?.coerceIn(0f, 1f),
    centerLabel = centerLabel,
    centerLabelAttribute = "current_humidity_label",
    supportingLabel = supportingLabel,
    supportingLabelAttribute = supportingLabel?.let { "humidity_label" },
    modeChip =
      HaModeChip.Static(
        entityId = entity?.entityId,
        attribute = "action_label",
        initial = modeChip,
      ),
    accent = Color(0xFF00ACC1),
    showSteppers = target != null,
    centerIcon = null,
    incrementAction = incAction,
    decrementAction = decAction,
  )
}

private fun stepperActions(
  entityId: String?,
  target: Float?,
  step: Float,
): Pair<HaAction, HaAction> {
  if (entityId == null || target == null) return HaAction.None to HaAction.None
  val up =
    HaAction.CallService(
      domain = "humidifier",
      service = "set_humidity",
      entityId = entityId,
      serviceData =
        kotlinx.serialization.json.JsonObject(
          mapOf(
            "humidity" to
              kotlinx.serialization.json.JsonPrimitive((target + step).coerceAtMost(100f))
          )
        ),
    )
  val down =
    HaAction.CallService(
      domain = "humidifier",
      service = "set_humidity",
      entityId = entityId,
      serviceData =
        kotlinx.serialization.json.JsonObject(
          mapOf(
            "humidity" to
              kotlinx.serialization.json.JsonPrimitive((target - step).coerceAtLeast(0f))
          )
        ),
    )
  return up to down
}
