package ee.schimke.ha.rc.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
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
 * `light` card — brightness ring around a bulb icon.
 *
 * Reads `brightness` (0..255) for the arc fill and uses on/off state to choose accent. Centre icon
 * is a bulb so the card reads visually as a light at a glance. Tap toggles the light; the +/-
 * steppers fire `light.turn_on` with `brightness_step` of 25 (~10%).
 *
 * Renders through the shared [RenderArcDial] ladder (same path as `thermostat` / `humidifier`) so
 * Fixed-mode launcher / Wear cells get the arc-left Wide row on narrow surfaces and the full
 * vertical card with steppers otherwise — the bulb-ring identity survives at every size instead of
 * stretching the full dial into short cells.
 */
class LightCardConverter : CardConverter {
  override val cardType: String = CardTypes.LIGHT

  // Baked, non-bindable content (see CardConverter.dataSignature):
  // re-encode when any referenced entity's snapshot data moves.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    cardDataSignature(cardEntityIds(card), snapshot)

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 250

  /**
   * A light renders from a card design. Its steppers, accent and labels follow whether it is on — a
   * discrete state the client keys on (`on`); brightness and labels are bindings ([DialCard]).
   */
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
  ): HaAction? {
    // IKEA's explicit power keys must remain explicit after replacing a template slot.
    // Brightness still uses DialCard's fresh snapshot-derived stepper payloads.
    if (
      snapshot != null &&
        slot == 0 &&
        templateAction is HaAction.CallService &&
        templateAction.domain == "light" &&
        templateAction.service in setOf("turn_on", "turn_off") &&
        templateAction.serviceData.isEmpty()
    ) {
      val id = card.raw["entity"]?.jsonPrimitive?.content ?: return null
      return templateAction.copy(entityId = id)
    }
    return dial.action(card, snapshot, templateAction)
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    // The dial's value already says on/off (`100%` / `Off`), and every light's dial looks alike: on
    // a watch the name is what tells two lights apart (card-information-hierarchy.md).
    RenderArcDial(lightDial(card, snapshot), modifier = modifier, wearShowsName = true)
  }

  private val dial =
    DialCard(
      cardType = cardType,
      domain = "light",
      configKeys = setOf("type", "entity", "name"),
      placeholder = { properties ->
        if (properties["on"] == "1") "on" to mapOf("brightness" to "128") else "off" to emptyMap()
      },
      data = ::lightDial,
      structure = { card, snapshot, _ ->
        val id = card.raw["entity"]?.jsonPrimitive?.content
        mapOf("on" to if (id != null && snapshot.states[id]?.state == "on") 1 else 0)
      },
    )
}

/** The light's dial for [card] and [snapshot]: what it draws, and what a template binds. */
internal fun lightDial(card: CardConfig, snapshot: HaSnapshot): HaArcDialData {
  val entityId = card.raw["entity"]?.jsonPrimitive?.content
  val entity = entityId?.let { snapshot.states[it] }
  val attrs = entity?.attributes ?: JsonObject(emptyMap())
  val name =
    card.raw["name"]?.jsonPrimitive?.content
      ?: attrs["friendly_name"]?.jsonPrimitive?.content
      ?: entityId
      ?: "Light"
  val isOn = entity?.state == "on"
  val brightness = attrs["brightness"]?.jsonPrimitive?.content?.toFloatOrNull()
  val fraction = if (isOn) (brightness ?: 255f) / 255f else 0f
  val accent = if (isOn) Color(0xFFFFBE3E) else Color(0xFFB0B0B0)
  val centerLabel = if (isOn) "${(fraction * 100f).toInt()}%" else "Off"
  // Static chip (not Toggle): a `RemoteStateLayout`-backed Toggle
  // chip nested inside the Fixed-mode `RenderArcDial` breakpoint
  // collapses to its captured branch (#224), painting "Off" over
  // the bulb on the full tier. Thermostat / humidifier use a Static
  // chip for the same reason; the on/off label is baked at capture
  // and refreshed on re-encode like every other arc-dial label.
  val modeChip =
    HaModeChip.Static(
      entityId = entityId,
      attribute = "is_on_label",
      initial = if (isOn) "On" else "Off",
    )

  val tap = entityId?.let { HaAction.Toggle(it) } ?: HaAction.None
  val (inc, dec) = brightnessSteppers(entityId, isOn, brightness)

  return HaArcDialData(
    entityId = entityId,
    name = name,
    valueFraction = fraction,
    targetFraction = null,
    centerLabel = centerLabel,
    centerLabelAttribute = "brightness_pct",
    supportingLabel = null,
    supportingLabelAttribute = null,
    modeChip = modeChip,
    accent = accent,
    showSteppers = isOn,
    centerIcon = Icons.Filled.Lightbulb,
    tapAction = tap,
    incrementAction = inc,
    decrementAction = dec,
  )
}

private fun brightnessSteppers(
  entityId: String?,
  isOn: Boolean,
  brightness: Float?,
): Pair<HaAction, HaAction> {
  if (entityId == null || !isOn) return HaAction.None to HaAction.None
  val current = brightness ?: 255f
  val step = 25f
  val up =
    HaAction.CallService(
      domain = "light",
      service = "turn_on",
      entityId = entityId,
      serviceData =
        kotlinx.serialization.json.JsonObject(
          mapOf(
            "brightness" to
              kotlinx.serialization.json.JsonPrimitive((current + step).coerceAtMost(255f).toInt())
          )
        ),
    )
  val down =
    HaAction.CallService(
      domain = "light",
      service = "turn_on",
      entityId = entityId,
      serviceData =
        kotlinx.serialization.json.JsonObject(
          mapOf(
            "brightness" to
              kotlinx.serialization.json.JsonPrimitive((current - step).coerceAtLeast(0f).toInt())
          )
        ),
    )
  return up to down
}
