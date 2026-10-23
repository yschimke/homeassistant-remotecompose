@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.temperatureUnitFor
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalFixedCanvasAxis
import ee.schimke.ha.rc.LocalInCardContainer
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import ee.schimke.ha.rc.RemoteMinSizeGate
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.WidgetCaptureSizeDp
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardEntityIds
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaArcDialData
import ee.schimke.ha.rc.components.HaModeChip
import ee.schimke.ha.rc.components.RemoteHaArcDial
import ee.schimke.ha.rc.components.RemoteHaArcDialFill
import ee.schimke.ha.rc.components.RemoteHaArcDialWide
import ee.schimke.ha.rc.components.immediateSwap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `thermostat` card — climate entity as a 270° arc dial.
 *
 * Reads `temperature` (target), `current_temperature`, `hvac_action`, `hvac_mode`, plus
 * `min_temp`/`max_temp` for the arc range. Tapping the +/- steppers fires `climate.set_temperature`
 * with `temperature` adjusted by `target_temp_step` (default 0.5°). The arc fill tracks the
 * *current* temperature; the target is shown as a textual supporting line.
 */
class ThermostatCardConverter : CardConverter {
  override val cardType: String = CardTypes.THERMOSTAT

  // Baked, non-bindable content (see CardConverter.dataSignature):
  // re-encode when any referenced entity's snapshot data moves, or the unit system the labels are
  // written in arrives.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    cardDataSignature(cardEntityIds(card), snapshot) + "|" + snapshot.temperatureUnit.orEmpty()

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 290

  /** A thermostat renders from a card design; see [DialCard]. */
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
    // On a watch the name tells two thermostats apart (card-information-hierarchy.md). The mode
    // chip says more than the accent here (`Idle` in heat mode is still orange), so Wear Large,
    // which has the room, keeps it under the name; Wear Small gives its line to the name.
    RenderArcDial(
      thermostatDial(card, snapshot),
      modifier,
      // In a stack or grid the container is the multi-entity card: its dials stay nameless.
      wearShowsName = !LocalInCardContainer.current,
      wearLargeKeepsMode = true,
    )
  }

  private val dial =
    DialCard(
      cardType = cardType,
      domain = "climate",
      configKeys = setOf("type", "entity", "name"),
      placeholder = { properties ->
        val hasTarget = properties[DialCard.HAS_TARGET] == "1"
        "heat" to
          buildMap {
            put("current_temperature", "19")
            if (hasTarget) put("temperature", "20")
          }
      },
      data = ::thermostatDial,
    )
}

/** The thermostat's dial for [card] and [snapshot]: what it draws, and what a template binds. */
internal fun thermostatDial(card: CardConfig, snapshot: HaSnapshot): HaArcDialData {
  val entityId = card.raw["entity"]?.jsonPrimitive?.content
  val entity = entityId?.let { snapshot.states[it] }
  val attrs = entity?.attributes ?: JsonObject(emptyMap())
  val name =
    card.raw["name"]?.jsonPrimitive?.content
      ?: attrs["friendly_name"]?.jsonPrimitive?.content
      ?: entityId
      ?: "Thermostat"
  // HA's unit: the entity's own, else the instance's unit system, else °C.
  val unit = snapshot.temperatureUnitFor(entity)
  val current = attrs["current_temperature"]?.jsonPrimitive?.content?.toFloatOrNull()
  val target = attrs["temperature"]?.jsonPrimitive?.content?.toFloatOrNull()
  val min = attrs["min_temp"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 7f
  val max = attrs["max_temp"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 35f
  val step = attrs["target_temp_step"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0.5f
  val mode = entity?.state ?: "off"
  val hvacAction = attrs["hvac_action"]?.jsonPrimitive?.content
  val modeChip =
    when (hvacAction) {
      "heating" -> "Heating"
      "cooling" -> "Cooling"
      "drying" -> "Drying"
      "fan" -> "Fan"
      "idle" -> "Idle"
      else -> mode.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
    }
  val accent = climateAccent(mode, hvacAction)
  val span = (max - min).coerceAtLeast(0.0001f)
  val valueFraction = ((current ?: target ?: min) - min) / span
  val targetFraction = target?.let { (it - min) / span }

  val centerLabel =
    current?.let { "${formatTemp(it)}$unit" } ?: target?.let { "${formatTemp(it)}$unit" } ?: "—"
  val supportingLabel = target?.let { t -> "↑ ${formatTemp(t)}$unit" }

  val (incAction, decAction) =
    stepperActions(entity, target, step) ?: (HaAction.None to HaAction.None)

  return HaArcDialData(
    entityId = entityId,
    name = name,
    valueFraction = valueFraction.coerceIn(0f, 1f),
    targetFraction = targetFraction?.coerceIn(0f, 1f),
    centerLabel = centerLabel,
    centerLabelAttribute = "current_temperature_label",
    supportingLabel = supportingLabel,
    supportingLabelAttribute = supportingLabel?.let { "temperature_label" },
    modeChip =
      HaModeChip.Static(
        entityId = entityId,
        attribute = "hvac_action_label",
        initial = modeChip,
      ),
    accent = accent,
    showSteppers = target != null,
    centerIcon = null,
    incrementAction = incAction,
    decrementAction = decAction,
    accentChoices = ClimateAccents,
  )
}

/**
 * Two-dimensional ladder shared by thermostat / humidifier / light: the arc-left Wide row handles a
 * cell that is too narrow or short for the full vertical card with steppers. The full card needs
 * both dimensions, so a short wide launcher does not hide its reading and actions below the cell. A
 * single threshold avoids the alpha010 nested `RemoteStateLayout` quirk that collapses multi-tier
 * ladders (#224).
 *
 * On a Wear slot ([LocalFixedCanvasAxis] is [BreakpointAxis.Height]) every container is narrower
 * than the full card, so the gate moves to height: the small slot, too short for the Wide row's
 * three lines, takes its compact two-line form.
 */
@Composable
internal fun RenderArcDial(
  data: HaArcDialData,
  modifier: RemoteModifier,
  /** Name the entity on a Wear slot (see [RemoteHaArcDialWide]'s `showName`). */
  wearShowsName: Boolean = false,
  /**
   * With [wearShowsName], keep the mode chip under the name in the Wear Large container (the Small
   * one has two lines, name and value): for a dial whose chip says more than its value and accent.
   */
  wearLargeKeepsMode: Boolean = false,
) {
  when (LocalCardSizeMode.current) {
    CardSizeMode.Wrap -> RemoteHaArcDial(data, modifier)
    CardSizeMode.Fixed ->
      when (LocalFixedCanvasAxis.current) {
        BreakpointAxis.Height ->
          RemoteSizeBreakpoint(
            thresholdsDp = intArrayOf(ArcDialWideMinHeightDp),
            axis = BreakpointAxis.Height,
            modifier = modifier.fillMaxSize(),
          ) { tier ->
            RemoteHaArcDialWide(
              data,
              RemoteModifier.fillMaxSize(),
              compact = tier == 0,
              dialDp = if (tier == 0) 56 else WEAR_LARGE_DIAL_DP,
              showName = wearShowsName,
              showMode = !wearShowsName || (wearLargeKeepsMode && tier != 0),
            )
          }
        BreakpointAxis.Width -> {
          // The name stays at every launcher size but 1×1 (card-information-hierarchy.md); a dial
          // inside a multi-entity container leaves naming to the container.
          val named = !LocalInCardContainer.current
          val capture = LocalWidgetCaptureSizeDp.current
          RemoteMinSizeGate(
            minWidthDp = ArcDialFullMinDp,
            minHeightDp = ArcDialFullMinHeightDp,
            modifier = modifier.fillMaxSize(),
          ) { roomy ->
            // Both gates swap at once: a state layout's default cross-fade leaves the AndroidX
            // player painting the other branch over the selected one (#309).
            RemoteBox(RemoteModifier.fillMaxSize().immediateSwap()) {
              if (roomy) RemoteHaArcDial(data, RemoteModifier.fillMaxSize())
              else
                RemoteMinSizeGate(
                  minWidthDp = ArcDialFillMinWidthDp,
                  minHeightDp = ArcDialFillMinHeightDp,
                  modifier = RemoteModifier.fillMaxSize(),
                ) { fills ->
                  RemoteBox(RemoteModifier.fillMaxSize().immediateSwap()) {
                    if (fills)
                      RemoteHaArcDialFill(
                        data,
                        RemoteModifier.fillMaxSize(),
                        showName = named,
                        compactCenter = capture != null && fillDialDp(capture) < ArcDialFillRoomyDp,
                      )
                    else
                      RemoteHaArcDialWide(
                        data,
                        RemoteModifier.fillMaxSize(),
                        // A one-column widget has no room beside the dial for the name; a wider row
                        // gives the mode chip's line to it, as a Wear slot does.
                        showName =
                          named && (capture?.widthDp ?: Int.MAX_VALUE) >= ArcDialFillMinWidthDp,
                      )
                  }
                }
            }
          }
        }
      }
  }
}

private const val ArcDialFullMinDp = 260

/**
 * The dial-filled tier ([RemoteHaArcDialFill]): two launcher columns (120 dp) and the name over a
 * dial of at least ~110 dp, which holds the centre readout at value size. Smaller, the Wide row.
 */
private const val ArcDialFillMinWidthDp = 120
private const val ArcDialFillMinHeightDp = 150

/** A filled dial this size or more holds the full centre readout; smaller takes the short one. */
private const val ArcDialFillRoomyDp = 120

/** The filled dial's side in a [capture]d launcher widget: the cell less its padding and name. */
private fun fillDialDp(capture: WidgetCaptureSizeDp): Int =
  minOf(capture.widthDp - 24, capture.heightDp - 20 - 24)

// Name, 180dp dial, 48dp stepper targets and chrome need the thermostat's natural height.
private const val ArcDialFullMinHeightDp = 290

/**
 * The Wide row's mode, value and target lines (~59dp: a line is ~1.33× its type size) inside its
 * 8dp vertical padding.
 */
private const val ArcDialWideMinHeightDp = 76

private fun stepperActions(
  entity: EntityState?,
  target: Float?,
  step: Float,
): Pair<HaAction, HaAction>? {
  val id = entity?.entityId ?: return null
  val current = target ?: return null
  val up =
    HaAction.CallService(
      domain = "climate",
      service = "set_temperature",
      entityId = id,
      serviceData =
        kotlinx.serialization.json.JsonObject(
          mapOf("temperature" to kotlinx.serialization.json.JsonPrimitive(current + step))
        ),
    )
  val down =
    HaAction.CallService(
      domain = "climate",
      service = "set_temperature",
      entityId = id,
      serviceData =
        kotlinx.serialization.json.JsonObject(
          mapOf("temperature" to kotlinx.serialization.json.JsonPrimitive(current - step))
        ),
    )
  return up to down
}

private val AccentHeat = Color(0xFFE65100)
private val AccentCool = Color(0xFF2196F3)
private val AccentDry = Color(0xFFFDD835)
private val AccentFan = Color(0xFF00BFA5)
private val AccentAuto = Color(0xFF546E7A)
private val AccentOff = Color(0xFF9E9E9E)

/** Every accent [climateAccent] can pick; a design template selects among them in-document. */
private val ClimateAccents =
  listOf(AccentHeat, AccentCool, AccentDry, AccentFan, AccentAuto, AccentOff)

/**
 * The accent a thermostat dial draws in for HVAC [mode] (`heat`, `cool`, …) and the entity's live
 * [hvacAction], which wins when it says the system is running.
 */
fun climateAccent(mode: String, hvacAction: String? = null): Color =
  when {
    hvacAction == "heating" || mode == "heat" -> AccentHeat
    hvacAction == "cooling" || mode == "cool" -> AccentCool
    hvacAction == "drying" || mode == "dry" -> AccentDry
    hvacAction == "fan" || mode == "fan_only" -> AccentFan
    mode == "auto" || mode == "heat_cool" -> AccentAuto
    else -> AccentOff
  }

private fun formatTemp(v: Float): String = decimalText(v.toDouble(), 1)

/** The arc dial's box on a Wear Large slot: as tall as its three lines, beside them. */
private const val WEAR_LARGE_DIAL_DP = 72
