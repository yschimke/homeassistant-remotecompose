@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.components.HaBambuPrintPhase
import ee.schimke.ha.rc.components.HaType
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.cardChrome
import ee.schimke.ha.rc.components.currentRemoteHaTheme
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.formatValueWithUnit
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One converter per HACS card from
 * [`greghesp/ha-bambulab-cards`](https://github.com/greghesp/ha-bambulab-cards).
 *
 * Card-name reference (from `src/cards/<name>/const.ts` with `PREFIX_NAME = "ha-bambulab"`):
 * - `custom:ha-bambulab-ams-card` — AMS spool grid
 * - `custom:ha-bambulab-spool-card` — single spool detail
 * - `custom:ha-bambulab-print_status-card` — current print job + progress
 * - `custom:ha-bambulab-print_control-card` — pause / resume / cancel pad (legacy alias
 *   `custom:ha-bambulab-skipobject-card`)
 *
 * Visual reference: card screenshots live on the integration's docs site
 * (`docs.page/greghesp/ha-bambulab`) — no inline assets in the cards repo.
 *
 * Today every variant renders the same chrome (variant label + the configured printer entity +
 * state). Override [Render] in a subclass once the per-variant visuals (AMS grid, progress ring,
 * control pad) are built.
 */
open class BambuLabCardConverter(
  final override val cardType: String,
  private val variantLabel: String,
) : CardConverter {

  // Signature for the chrome-only fallback render (variant label + the configured
  // printer entity + state) — baked, not bound (see CardConverter.dataSignature).
  // The id lives under `printer:` or `entity:`, which cardEntityIds doesn't walk.
  // Subclasses that discover sensors via resolvePrinterPrefix override this to base
  // the signature on the entities they actually render.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String {
    val printer = card.raw["printer"]?.jsonPrimitive?.content
    val id = printer ?: card.raw["entity"]?.jsonPrimitive?.content ?: return ""
    return cardDataSignature(setOf(id), snapshot)
  }

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 96

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val theme = currentRemoteHaTheme()
    val entityId =
      card.raw["printer"]?.jsonPrimitive?.content ?: card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val name =
      entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
        ?: entityId
        ?: "Bambu Lab printer"
    val state = formatState(entity, snapshot)

    RemoteBox(
      modifier =
        modifier
          .fillMaxWidth()
          .then(cardChrome(theme.cardBackground, theme.divider))
          .padding(horizontal = 12.rdp, vertical = 10.rdp)
    ) {
      RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(4.rdp)) {
        RemoteText(
          text = "Bambu Lab".rs,
          color = theme.secondaryText,
          fontSize = HaType.Caption,
          style = RemoteTextStyle.Default,
        )
        RemoteText(
          text = variantLabel.rs,
          color = theme.primaryText,
          fontSize = HaType.Title,
          fontWeight = FontWeight.Medium,
          style = RemoteTextStyle.Default,
        )
        RemoteRow(
          modifier = RemoteModifier.fillMaxWidth(),
          verticalAlignment = RemoteAlignment.CenterVertically,
          horizontalArrangement = RemoteArrangement.SpaceBetween,
        ) {
          RemoteText(
            text = name.rs,
            color = theme.primaryText,
            fontSize = HaType.Body,
            style = RemoteTextStyle.Default,
          )
          RemoteText(
            text = LiveValues.state(entity?.entityId, state),
            color = theme.primaryText,
            fontSize = HaType.Body,
            style = RemoteTextStyle.Default,
          )
        }
      }
    }
  }
}

/**
 * AMS spool grid. Discovers tray entities `sensor.<prefix>_ams_<n>_tray_<m>` where n defaults to 1;
 * the card config's `ams:` device id isn't resolvable without a registry channel, so we use the
 * first AMS that matches and rely on `entity_prefix:` for disambiguation.
 */
class BambuLabAmsCardConverter :
  BambuLabCardConverter(cardType = "custom:ha-bambulab-ams-card", variantLabel = "AMS") {
  /**
   * An AMS grid renders from a card design: which trays exist, which is active and whether each
   * reports what remains; materials, remaining labels and spool colours are bindings. The template
   * names its trays with [BambuDesign.PREFIX]; the client renames its printer's onto it.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    val prefix = resolvePrinterPrefix(card, snapshot) ?: return null
    val slots = readSpoolSlots(prefix, snapshot, amsIndex = 1)
    if (slots.isEmpty()) return null
    return DesignProperties.build {
      slots.forEach { slot ->
        val tray = slot.entityId!!.substringAfterLast('_')
        enum("t$tray", if (slot.active) 2 else 1)
        enum("t$tray.remain", if (slot.remainPercent != null) 1 else 0)
      }
    }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    BambuDesign.amsTemplate(cardType, properties)

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    val prefix = resolvePrinterPrefix(card, snapshot) ?: return BambuDesign.NONE
    val slots = readSpoolSlots(prefix, snapshot, amsIndex = 1)
    return BambuDesign.slotBindings(slots.associateWith { it.entityId!!.renamePrefix(prefix) })
  }

  // Renders discovered `sensor.<prefix>_ams_1_tray_*`, not the configured printer
  // entity — base over the discovered trays so live tray changes re-encode.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String {
    val prefix = resolvePrinterPrefix(card, snapshot) ?: return super.dataSignature(card, snapshot)
    return cardDataSignature(amsTrayEntityIds(prefix), snapshot)
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val prefix = resolvePrinterPrefix(card, snapshot)
    if (prefix == null) {
      super.Render(card, snapshot, modifier)
      return
    }
    val slots = readSpoolSlots(prefix, snapshot, amsIndex = 1)
    if (slots.isEmpty()) {
      super.Render(card, snapshot, modifier)
      return
    }
    ee.schimke.ha.rc.components.RemoteHaBambuAms(
      ee.schimke.ha.rc.components.HaBambuAmsData(title = "AMS", slots = slots),
      modifier,
    )
  }
}

/**
 * Single-spool detail. The card config has `spool:<id>`; we don't have a spool↔entity map, so we
 * surface the active tray (or tray 1 if none active). The fixture path is the most reliable; live
 * HA users can override with `entity_prefix:` + `slot:` to pin a specific tray.
 */
class BambuLabSpoolCardConverter :
  BambuLabCardConverter(cardType = "custom:ha-bambulab-spool-card", variantLabel = "Spool") {
  /** A spool detail renders from a card design, as one AMS tray: see [BambuLabAmsCardConverter]. */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    val slot = chosenSlot(card, snapshot) ?: return null
    return DesignProperties.build {
      enum("t1", if (slot.active) 2 else 1)
      enum("t1.remain", if (slot.remainPercent != null) 1 else 0)
    }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    BambuDesign.amsTemplate(cardType, properties)

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    val slot = chosenSlot(card, snapshot) ?: return BambuDesign.NONE
    // The template shows its one tray as tray 1, whichever tray the client chose.
    val bindings = BambuDesign.slotBindings(mapOf(slot to BambuDesign.trayId(1)))
    return bindings +
      CardSnapshotBindings(
        mapOf("card.slot_label" to slot.slotLabel),
        emptyMap(),
        emptyMap(),
        emptyMap(),
      )
  }

  private fun chosenSlot(
    card: CardConfig,
    snapshot: HaSnapshot,
  ): ee.schimke.ha.rc.components.HaBambuSpoolSlot? {
    val prefix = resolvePrinterPrefix(card, snapshot) ?: return null
    val slots = readSpoolSlots(prefix, snapshot, amsIndex = 1)
    val pinnedSlot = card.raw["slot"]?.jsonPrimitive?.content?.toIntOrNull()
    return pinnedSlot?.let { idx -> slots.getOrNull(idx - 1) }
      ?: slots.firstOrNull { it.active }
      ?: slots.firstOrNull()
  }

  // Renders one of the discovered `sensor.<prefix>_ams_1_tray_*` slots — base over
  // the whole tray set so a change to any (or to which is active) re-encodes.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String {
    val prefix = resolvePrinterPrefix(card, snapshot) ?: return super.dataSignature(card, snapshot)
    return cardDataSignature(amsTrayEntityIds(prefix), snapshot)
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val prefix = resolvePrinterPrefix(card, snapshot)
    if (prefix == null) {
      super.Render(card, snapshot, modifier)
      return
    }
    val slots = readSpoolSlots(prefix, snapshot, amsIndex = 1)
    val pinnedSlot = card.raw["slot"]?.jsonPrimitive?.content?.toIntOrNull()
    val slot =
      pinnedSlot?.let { idx -> slots.getOrNull(idx - 1) }
        ?: slots.firstOrNull { it.active }
        ?: slots.firstOrNull()
    if (slot == null) {
      super.Render(card, snapshot, modifier)
      return
    }
    ee.schimke.ha.rc.components.RemoteHaBambuSpool(
      ee.schimke.ha.rc.components.HaBambuSpoolDetail(slot = slot),
      modifier,
    )
  }
}

private fun readSpoolSlots(
  prefix: String,
  snapshot: HaSnapshot,
  amsIndex: Int,
): List<ee.schimke.ha.rc.components.HaBambuSpoolSlot> {
  val s = snapshot.states
  return (1..4).mapNotNull { trayIdx ->
    val key = "sensor.${prefix}_ams_${amsIndex}_tray_$trayIdx"
    val entity = s[key] ?: return@mapNotNull null
    val attrs = entity.attributes
    val material = entity.state.takeUnless { it == "unknown" || it == "unavailable" } ?: "Empty"
    val color = parseHexArgb(attrs["color"]?.jsonPrimitive?.content)
    val remain =
      (attrs["remain"]?.jsonPrimitive?.content?.toIntOrNull())?.takeIf {
        attrs["remain_enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() != false
      }
    val active = attrs["active"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
    ee.schimke.ha.rc.components.HaBambuSpoolSlot(
      entityId = entity.entityId,
      slotLabel = "Slot $trayIdx",
      material = LiveValues.state(entity.entityId, material),
      color = color,
      remainPercent = remain,
      active = active,
      materialText = material,
    )
  }
}

/**
 * Parse `#RRGGBB` / `#RRGGBBAA` into a Compose [androidx.compose.ui.graphics.Color]. Returns
 * mid-grey for null / unparseable input — callers paint the swatch with this so an absent attribute
 * still produces a recognisable slot.
 */
private fun parseHexArgb(hex: String?): androidx.compose.ui.graphics.Color {
  val fallback = androidx.compose.ui.graphics.Color(0xFF9E9E9E)
  val raw = hex?.trim()?.removePrefix("#") ?: return fallback
  return when (raw.length) {
    6 -> {
      val r = raw.substring(0, 2).toIntOrNull(16) ?: return fallback
      val g = raw.substring(2, 4).toIntOrNull(16) ?: return fallback
      val b = raw.substring(4, 6).toIntOrNull(16) ?: return fallback
      androidx.compose.ui.graphics.Color(r, g, b)
    }
    8 -> {
      val r = raw.substring(0, 2).toIntOrNull(16) ?: return fallback
      val g = raw.substring(2, 4).toIntOrNull(16) ?: return fallback
      val b = raw.substring(4, 6).toIntOrNull(16) ?: return fallback
      val a = raw.substring(6, 8).toIntOrNull(16) ?: 0xFF
      androidx.compose.ui.graphics.Color(r, g, b, a)
    }
    else -> fallback
  }
}

/**
 * Current print job + progress.
 *
 * HA's bambulab integration exposes a flock of `sensor.<prefix>_*` entities per printer; the card
 * config references a device id that we can't resolve without a device-registry channel. We
 * discover the prefix by scanning the snapshot for the first `sensor.*_print_progress` and pull
 * related sensors by suffix. Configurations with multiple printers can override via an
 * `entity_prefix:` config field.
 */
class BambuLabPrintStatusCardConverter :
  BambuLabCardConverter(
    cardType = "custom:ha-bambulab-print_status-card",
    variantLabel = "Print status",
  ) {
  /**
   * The print status renders from a card design: the printer's phase (printing, or idle / finished
   * / failed / offline, which draw without the ring), which lines exist (progress, layers, time
   * left, nozzle, bed) and whether the progress arc has started are structure; every line, the
   * progress, the state and the printer's name are bindings, under [BambuDesign.PREFIX].
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    val prefix = resolvePrinterPrefix(card, snapshot) ?: return null
    val data = buildPrintStatusData(prefix, snapshot)
    return DesignProperties.build {
      enum("phase", data.phase.ordinal)
      enum(
        "progress",
        if (data.progressEntityId == null) 0 else if (data.progressFraction * 360f > 0.5f) 2 else 1,
      )
      enum("layer", if (data.layerLine != null) 1 else 0)
      enum("remaining", if (data.remainingLine != null) 1 else 0)
      enum("nozzle", if (data.nozzleLine != null) 1 else 0)
      enum("bed", if (data.bedLine != null) 1 else 0)
    }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    val progress = properties["progress"]?.toIntOrNull() ?: return null
    val phase =
      properties["phase"]?.toIntOrNull()?.let { HaBambuPrintPhase.entries.getOrNull(it) }
        ?: HaBambuPrintPhase.Printing
    val states = buildList {
      add(BambuDesign.sensor("current_stage", "printing"))
      // The phase is read from `print_status` first, so the template states it there.
      add(BambuDesign.sensor("print_status", phase.templateStatus))
      if (progress > 0) add(BambuDesign.sensor("print_progress", if (progress == 2) "50" else "0"))
      if (properties["layer"] == "1") {
        add(BambuDesign.sensor("current_layer", "1"))
        add(BambuDesign.sensor("total_layer_count", "2"))
      }
      if (properties["remaining"] == "1") add(BambuDesign.sensor("remaining_time", "1"))
      if (properties["nozzle"] == "1") add(BambuDesign.sensor("nozzle_temperature", "1"))
      if (properties["bed"] == "1") add(BambuDesign.sensor("bed_temperature", "1"))
    }
    return BambuDesign.template(cardType, states)
  }

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    val prefix = resolvePrinterPrefix(card, snapshot) ?: return BambuDesign.NONE
    val data = buildPrintStatusData(prefix, snapshot)
    // The template's entities are fixed: progress (else status), stage, current layer, ….
    val strings = buildMap {
      put("card.printer", data.printerName)
      put("${BambuDesign.sensorId("current_stage")}.attributes.stage_label", data.stage)
      if (data.progressEntityId != null) {
        put(
          "${BambuDesign.sensorId("print_progress")}.attributes.progress_label",
          data.progressLabel,
        )
      }
      data.layerLine?.let {
        put("${BambuDesign.sensorId("current_layer")}.attributes.layer_line", it)
      }
      data.remainingLine?.let {
        put("${BambuDesign.sensorId("remaining_time")}.attributes.remaining_line", it)
      }
      data.nozzleLine?.let {
        put("${BambuDesign.sensorId("nozzle_temperature")}.attributes.nozzle_line", it)
      }
      data.bedLine?.let {
        put("${BambuDesign.sensorId("bed_temperature")}.attributes.bed_line", it)
      }
    }
    val floats = buildMap {
      if (data.progressEntityId != null) {
        put("${BambuDesign.sensorId("print_progress")}.progress_fraction", data.progressFraction)
      }
    }
    return CardSnapshotBindings(strings, emptyMap(), emptyMap(), floats)
  }

  // Renders discovered `sensor.<prefix>_*` (progress, stage, layers, temps), not the
  // configured printer entity — base over those so live print data re-encodes.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String {
    val prefix = resolvePrinterPrefix(card, snapshot) ?: return super.dataSignature(card, snapshot)
    return cardDataSignature(printStatusEntityIds(prefix), snapshot)
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val prefix = resolvePrinterPrefix(card, snapshot)
    if (prefix == null) {
      super.Render(card, snapshot, modifier)
      return
    }
    val data = buildPrintStatusData(prefix, snapshot)
    when (LocalCardSizeMode.current) {
      // Dashboard: the two-column layout, so the job's lines use the right half.
      CardSizeMode.Wrap -> ee.schimke.ha.rc.components.RemoteHaBambuPrintStatus(data, modifier)
      // Launcher / Wear: a narrow cell stacks the same content in a column; a wide one lays it out
      // in two. One width gate (#224).
      CardSizeMode.Fixed ->
        RemoteSizeBreakpoint(
          thresholdsDp = intArrayOf(PRINT_STATUS_WIDE_MIN_DP),
          modifier = modifier,
          axis = BreakpointAxis.Width,
        ) { tier ->
          ee.schimke.ha.rc.components.RemoteHaBambuPrintStatus(
            data,
            RemoteModifier.fillMaxWidth(),
            wide = tier > 0,
          )
        }
    }
  }
}

/** Width (dp) from which the print status lays its content out in two columns. */
private const val PRINT_STATUS_WIDE_MIN_DP = 260

/**
 * Best-effort discovery: explicit `entity_prefix` wins; otherwise the first
 * `sensor.*_print_progress` entity's prefix. Returns null when the snapshot has no Bambu-shaped
 * entities, in which case the caller falls back to the chrome-only renderer.
 */
internal fun resolvePrinterPrefix(card: CardConfig, snapshot: HaSnapshot): String? {
  val explicit = card.raw["entity_prefix"]?.jsonPrimitive?.content
  if (!explicit.isNullOrEmpty()) return explicit
  // Try the most discriminating sensors first; fall through so an
  // AMS- or controls-only snapshot still resolves.
  val sensorSuffixes = listOf("_print_progress", "_print_status", "_current_stage")
  for (suffix in sensorSuffixes) {
    val hit = snapshot.states.keys.firstOrNull { it.startsWith("sensor.") && it.endsWith(suffix) }
    if (hit != null) return hit.removePrefix("sensor.").removeSuffix(suffix)
  }
  // AMS tray sensors look like sensor.<prefix>_ams_<n>_tray_<m>.
  val tray =
    snapshot.states.keys.firstOrNull {
      it.startsWith("sensor.") && Regex(".*_ams_\\d+_tray_\\d+$").matches(it)
    }
  if (tray != null) {
    return tray.removePrefix("sensor.").substringBeforeLast("_ams_")
  }
  // Print-control buttons.
  val btn =
    snapshot.states.keys.firstOrNull { it.startsWith("button.") && it.endsWith("_pause_printing") }
  if (btn != null) return btn.removePrefix("button.").removeSuffix("_pause_printing")
  return null
}

/** Tray sensors the AMS / spool render paths read (`sensor.<prefix>_ams_1_tray_1..4`). */
private fun amsTrayEntityIds(prefix: String): Set<String> =
  (1..4).map { "sensor.${prefix}_ams_1_tray_$it" }.toSet()

/** Sensors [buildPrintStatusData] reads for the print-status card. */
private fun printStatusEntityIds(prefix: String): Set<String> = buildSet {
  add("sensor.${prefix}_print_progress")
  add("sensor.${prefix}_current_stage")
  add("sensor.${prefix}_print_status")
  add("sensor.${prefix}_current_layer")
  add("sensor.${prefix}_total_layer_count")
  add("sensor.${prefix}_remaining_time")
  add("sensor.${prefix}_nozzle_temperature")
  add("sensor.${prefix}_target_nozzle_temperature")
  add("sensor.${prefix}_bed_temperature")
  add("sensor.${prefix}_target_bed_temperature")
  add("sensor.${prefix}_printer_name")
  add("binary_sensor.${prefix}_online")
}

/** Buttons + name sensors [buildPrintControlData] reads for the control pad. */
private fun printControlEntityIds(prefix: String): Set<String> = buildSet {
  add("button.${prefix}_pause_printing")
  add("button.${prefix}_resume_printing")
  add("button.${prefix}_stop_printing")
  // Every source [bambuPrinterName] reads, so a renamed printer re-encodes the pad.
  add("sensor.${prefix}_printer_name")
  BambuNameSources.forEach { (suffix, _) -> add("sensor.${prefix}_$suffix") }
}

/**
 * Whole minutes left on the job. The integration states `remaining_time` in hours (`h`) today and
 * in minutes in older releases; its unit says which, and a missing unit is read as minutes.
 */
private fun remainingMinutes(entity: EntityState): Int? {
  val value = entity.state.toFloatOrNull()?.takeIf { it >= 0f } ?: return null
  val unit = entity.attributes["unit_of_measurement"]?.jsonPrimitive?.contentOrNull
  val minutes =
    when (unit) {
      "h" -> value * 60f
      "s" -> value / 60f
      "d" -> value * 1440f
      else -> value
    }
  return kotlin.math.round(minutes).toInt()
}

internal fun buildPrintStatusData(
  prefix: String,
  snapshot: HaSnapshot,
): ee.schimke.ha.rc.components.HaBambuPrintStatusData {
  val s = snapshot.states
  fun sensor(suffix: String) = s["sensor.${prefix}_$suffix"]

  val progressEntity = sensor("print_progress")
  val progress = progressEntity?.state?.toFloatOrNull() ?: 0f
  val stageEntity = sensor("current_stage") ?: sensor("print_status")
  val stageRaw = stageEntity?.state ?: "idle"

  val currentLayerEntity = sensor("current_layer")
  val totalLayerEntity = sensor("total_layer_count")
  val current = currentLayerEntity?.state?.toIntOrNull()
  val total = totalLayerEntity?.state?.toIntOrNull()

  val phase =
    bambuPrintPhase(
      printStatus = sensor("print_status")?.state,
      stage = sensor("current_stage")?.state,
      online = s["binary_sensor.${prefix}_online"]?.state,
      progress = progress,
      totalLayers = total,
    )
  val printing = phase == HaBambuPrintPhase.Printing
  // Printing shows the stage ("Inspecting first layer"); otherwise the state is the headline.
  val stage =
    if (printing) stageRaw.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
    else phase.label

  val printerEntity = progressEntity ?: sensor("print_status")
  val printerName = bambuPrinterName(prefix, snapshot)

  // The job's lines exist only while printing: an idle printer's `Layer 0 / 0` and `0m left`
  // describe no job.
  val layerLine =
    if (printing && current != null && total != null) "Layer $current / $total" else null

  val remainingEntity = sensor("remaining_time")
  val remaining = remainingEntity?.let(::remainingMinutes)?.takeIf { printing }
  val remainingLine = remaining?.let { mins ->
    val h = mins / 60
    val m = mins % 60
    if (h > 0) "${h}h ${m}m left" else "${m}m left"
  }

  val nozzleEntity = sensor("nozzle_temperature")
  val nozzle = nozzleEntity?.state?.toFloatOrNull()
  val nozzleUnit =
    nozzleEntity?.attributes?.get("unit_of_measurement")?.jsonPrimitive?.content ?: "°C"
  val nozzleTarget = sensor("target_nozzle_temperature")?.state?.toFloatOrNull()
  val nozzleLine = nozzle?.let {
    val tgt =
      nozzleTarget?.let { t ->
        if (t > 0f) " → ${formatValueWithUnit(formatTemp(t), nozzleUnit)}" else ""
      } ?: ""
    "Nozzle ${formatValueWithUnit(formatTemp(it), nozzleUnit)}$tgt"
  }

  val bedEntity = sensor("bed_temperature")
  val bed = bedEntity?.state?.toFloatOrNull()
  val bedUnit = bedEntity?.attributes?.get("unit_of_measurement")?.jsonPrimitive?.content ?: "°C"
  val bedTarget = sensor("target_bed_temperature")?.state?.toFloatOrNull()
  val bedLine = bed?.let {
    val tgt =
      bedTarget?.let { t ->
        if (t > 0f) " → ${formatValueWithUnit(formatTemp(t), bedUnit)}" else ""
      } ?: ""
    "Bed ${formatValueWithUnit(formatTemp(it), bedUnit)}$tgt"
  }

  return ee.schimke.ha.rc.components.HaBambuPrintStatusData(
    entityId = printerEntity?.entityId,
    printerName = printerName,
    stageEntityId = stageEntity?.entityId,
    stage = stage,
    progressEntityId = progressEntity?.entityId,
    progressLabel = "${progress.toInt()} %",
    progressFraction = (progress / 100f).coerceIn(0f, 1f),
    accent = androidx.compose.ui.graphics.Color(0xFFFF8F00),
    layerEntityId = currentLayerEntity?.entityId ?: totalLayerEntity?.entityId,
    layerLine = layerLine,
    remainingEntityId = remainingEntity?.entityId,
    remainingLine = remainingLine,
    nozzleEntityId = nozzleEntity?.entityId,
    nozzleLine = nozzleLine,
    bedEntityId = bedEntity?.entityId,
    bedLine = bedLine,
    phase = phase,
  )
}

/**
 * The printer's phase. `print_status` (the printer's `gcode_state`: `running`, `pause`, `prepare`,
 * `finish`, `failed`, `idle`, `offline`) decides when it can; else `current_stage`; else a print
 * with no progress and no layers is not running.
 */
internal fun bambuPrintPhase(
  printStatus: String?,
  stage: String?,
  online: String?,
  progress: Float,
  totalLayers: Int?,
): HaBambuPrintPhase {
  if (online == "off") return HaBambuPrintPhase.Offline
  fun of(state: String?): HaBambuPrintPhase? =
    when (state?.trim()?.lowercase()) {
      null,
      "",
      "unknown" -> null
      "idle",
      "init" -> HaBambuPrintPhase.Idle
      "finish",
      "finished" -> HaBambuPrintPhase.Finished
      "failed" -> HaBambuPrintPhase.Failed
      "offline",
      "unavailable" -> HaBambuPrintPhase.Offline
      else -> HaBambuPrintPhase.Printing
    }
  return of(printStatus)
    ?: of(stage)
    ?: if (progress <= 0f && (totalLayers ?: 0) <= 0) HaBambuPrintPhase.Idle
    else HaBambuPrintPhase.Printing
}

/** The headline a printer that is not printing shows. */
private val HaBambuPrintPhase.label: String
  get() =
    when (this) {
      HaBambuPrintPhase.Printing -> "Printing"
      HaBambuPrintPhase.Idle -> "Idle"
      HaBambuPrintPhase.Finished -> "Finished"
      HaBambuPrintPhase.Failed -> "Failed"
      HaBambuPrintPhase.Offline -> "Offline"
    }

/** A `print_status` a design template states for [this] phase: [bambuPrintPhase] reads it back. */
private val HaBambuPrintPhase.templateStatus: String
  get() =
    when (this) {
      HaBambuPrintPhase.Printing -> "running"
      HaBambuPrintPhase.Idle -> "idle"
      HaBambuPrintPhase.Finished -> "finish"
      HaBambuPrintPhase.Failed -> "failed"
      HaBambuPrintPhase.Offline -> "offline"
    }

/**
 * The printer's display name. The name the owner gave it (`sensor.<prefix>_printer_name`) when it
 * is not Bambu's factory default (`3DP-030-293`); else the device's name, read off an entity's
 * friendly name (`A1MINI_sn1 Print progress` → `A1MINI_sn1`), with its serial dropped and its model
 * code spelled as Bambu does (`A1MINI_01P00A…` → `A1 mini`). A device the owner renamed keeps its
 * name.
 */
internal fun bambuPrinterName(prefix: String, snapshot: HaSnapshot): String {
  val s = snapshot.states
  s["sensor.${prefix}_printer_name"]
    ?.state
    ?.trim()
    ?.takeIf { it.isNotEmpty() && it !in NotAName && !FactoryPrinterName.matches(it) }
    ?.let {
      return it
    }
  val device =
    BambuNameSources.firstNotNullOfOrNull { (suffix, entityName) ->
      s["sensor.${prefix}_$suffix"]
        ?.attributes
        ?.get("friendly_name")
        ?.jsonPrimitive
        ?.content
        ?.trim()
        ?.takeIf { it.endsWith(" $entityName", ignoreCase = true) }
        ?.dropLast(entityName.length + 1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    } ?: prefix
  val serialSplit = DeviceWithSerial.matchEntire(device)
  val model = serialSplit?.groupValues?.get(1) ?: device
  return BambuModelNames[model.uppercase()]
    ?: if (serialSplit != null) model.uppercase() else device
}

private val NotAName = setOf("unknown", "unavailable", "none")

/** Bambu Handy's default printer name, which says nothing a person would recognise. */
private val FactoryPrinterName = Regex("""3DP-\d{3}-\d{3}""")

/**
 * `<MODEL>_<serial>`, the bambulab integration's device name: a real serial (`01P00A123456789`) or
 * a redacted one (`sn1`).
 */
private val DeviceWithSerial = Regex("""([A-Za-z0-9]+)_(sn\d+|[0-9A-Z]{8,})""")

/** Entities whose friendly name is `<device> <entity name>`, and that entity name. */
private val BambuNameSources =
  listOf(
    "print_progress" to "Print progress",
    "print_status" to "Print status",
    "current_stage" to "Current stage",
  )

/** Model codes as Bambu Lab writes the printers' names. */
private val BambuModelNames =
  mapOf(
    "A1MINI" to "A1 mini",
    "A1" to "A1",
    "P1P" to "P1P",
    "P1S" to "P1S",
    "P2S" to "P2S",
    "X1" to "X1",
    "X1C" to "X1 Carbon",
    "X1E" to "X1E",
    "H2D" to "H2D",
    "H2S" to "H2S",
  )

private fun formatTemp(value: Float): String =
  if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)

/**
 * Pause / resume / cancel pad. Wires the printer's three button entities into a 3-button row; tap
 * fires `button.press` on the matching entity. Falls back to chrome-only when the snapshot has no
 * Bambu-shaped buttons.
 */
class BambuLabPrintControlCardConverter :
  BambuLabCardConverter(
    cardType = "custom:ha-bambulab-print_control-card",
    variantLabel = "Print control",
  ) {
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    BambuDesign.controlProperties(card, snapshot)

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    BambuDesign.controlTemplate(cardType, properties)

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings =
    BambuDesign.controlBindings(card, snapshot)

  override fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: ee.schimke.ha.rc.components.HaAction,
  ): ee.schimke.ha.rc.components.HaAction? =
    BambuDesign.controlAction(card, snapshot, templateAction)

  // Renders discovered `button.<prefix>_*` controls + a name sensor, not the
  // configured printer entity — base over those so availability changes re-encode.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String {
    val prefix = resolvePrinterPrefix(card, snapshot) ?: return super.dataSignature(card, snapshot)
    return cardDataSignature(printControlEntityIds(prefix), snapshot)
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val data = buildPrintControlData(card, snapshot)
    if (data == null) {
      super.Render(card, snapshot, modifier)
      return
    }
    ee.schimke.ha.rc.components.RemoteHaBambuPrintControl(data, modifier)
  }
}

/** Legacy alias of print_control card from earlier versions. */
class BambuLabSkipObjectCardConverter :
  BambuLabCardConverter(
    cardType = "custom:ha-bambulab-skipobject-card",
    variantLabel = "Print control",
  ) {
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    BambuDesign.controlProperties(card, snapshot)

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    BambuDesign.controlTemplate(cardType, properties)

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings =
    BambuDesign.controlBindings(card, snapshot)

  override fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: ee.schimke.ha.rc.components.HaAction,
  ): ee.schimke.ha.rc.components.HaAction? =
    BambuDesign.controlAction(card, snapshot, templateAction)

  // Renders discovered `button.<prefix>_*` controls + a name sensor, not the
  // configured printer entity — base over those so availability changes re-encode.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String {
    val prefix = resolvePrinterPrefix(card, snapshot) ?: return super.dataSignature(card, snapshot)
    return cardDataSignature(printControlEntityIds(prefix), snapshot)
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val data = buildPrintControlData(card, snapshot)
    if (data == null) {
      super.Render(card, snapshot, modifier)
      return
    }
    ee.schimke.ha.rc.components.RemoteHaBambuPrintControl(data, modifier)
  }
}

private fun buildPrintControlData(
  card: CardConfig,
  snapshot: HaSnapshot,
): ee.schimke.ha.rc.components.HaBambuPrintControlData? {
  val prefix = resolvePrinterPrefix(card, snapshot) ?: return null
  val s = snapshot.states
  fun btn(suffix: String) = s["button.${prefix}_$suffix"]?.entityId

  val pause =
    btn("pause_printing")?.let {
      ee.schimke.ha.rc.components.HaBambuControlButton(
        label = "Pause",
        icon = Icons.Filled.Pause,
        accent = androidx.compose.ui.graphics.Color(0xFFFFA000),
        tapAction =
          ee.schimke.ha.rc.components.HaAction.CallService(
            domain = "button",
            service = "press",
            entityId = it,
            serviceData = kotlinx.serialization.json.JsonObject(emptyMap()),
          ),
      )
    }
  val resume =
    btn("resume_printing")?.let {
      ee.schimke.ha.rc.components.HaBambuControlButton(
        label = "Resume",
        icon = Icons.Filled.PlayArrow,
        accent = androidx.compose.ui.graphics.Color(0xFF43A047),
        tapAction =
          ee.schimke.ha.rc.components.HaAction.CallService(
            domain = "button",
            service = "press",
            entityId = it,
            serviceData = kotlinx.serialization.json.JsonObject(emptyMap()),
          ),
      )
    }
  val stop =
    btn("stop_printing")?.let {
      ee.schimke.ha.rc.components.HaBambuControlButton(
        label = "Stop",
        icon = Icons.Filled.Stop,
        accent = androidx.compose.ui.graphics.Color(0xFFE53935),
        tapAction =
          ee.schimke.ha.rc.components.HaAction.CallService(
            domain = "button",
            service = "press",
            entityId = it,
            serviceData = kotlinx.serialization.json.JsonObject(emptyMap()),
          ),
      )
    }
  if (pause == null && resume == null && stop == null) return null

  val printerName = bambuPrinterName(prefix, snapshot)
  return ee.schimke.ha.rc.components.HaBambuPrintControlData(
    printerName = printerName,
    pause = pause,
    resume = resume,
    stop = stop,
  )
}

/**
 * Card design support for the Bambu Lab cards. They find their printer's entities by prefix in the
 * snapshot (`sensor.<prefix>_print_progress`, …) rather than config, so a template names them with
 * the placeholder [PREFIX] (`entity_prefix: rcslot`) and the client renames its printer's entities
 * onto it — the discovery is the client's, on live data, and never part of a template.
 */
internal object BambuDesign {
  const val PREFIX = "rcslot"
  val NONE = CardSnapshotBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap())

  fun sensorId(suffix: String) = "sensor.${PREFIX}_$suffix"

  fun trayId(tray: Int) = sensorId("ams_1_tray_$tray")

  fun sensor(suffix: String, state: String, attributes: Map<String, String> = emptyMap()) =
    CardDesignSlots.templateEntity(sensorId(suffix), state, attributes)

  fun template(
    cardType: String,
    states: List<ee.schimke.ha.model.EntityState>,
  ): DesignTemplateInput {
    val card =
      CardConfig(
        type = cardType,
        raw =
          kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive(cardType))
            put("entity_prefix", kotlinx.serialization.json.JsonPrimitive(PREFIX))
          },
      )
    return DesignTemplateInput(card, HaSnapshot(states = states.associateBy { it.entityId }))
  }

  /** AMS / spool trays: `t<n>` is 1 for a tray, 2 for the active tray; `t<n>.remain` its remain. */
  fun amsTemplate(cardType: String, properties: DesignProperties): DesignTemplateInput? {
    val trays = (1..4).filter { properties["t$it"] != null }
    if (trays.isEmpty()) return null
    val states = trays.map { tray ->
      CardDesignSlots.templateEntity(
        trayId(tray),
        placeholderState = "PLA",
        attributes =
          buildMap {
            put("color", "#808080")
            put("active", (properties["t$tray"] == "2").toString())
            if (properties["t$tray.remain"] == "1") put("remain", "50")
            else put("remain_enabled", "false")
          },
      )
    }
    return template(cardType, states)
  }

  /** Each tray's bindings, renamed onto its template tray. */
  fun slotBindings(
    trays: Map<ee.schimke.ha.rc.components.HaBambuSpoolSlot, String>
  ): CardSnapshotBindings {
    val strings = LinkedHashMap<String, String>()
    val floats = LinkedHashMap<String, Float>()
    for ((slot, templateId) in trays) {
      if (slot.entityId == null) continue
      strings["$templateId.state"] = slotMaterial(slot)
      strings["$templateId.attributes.remain_label"] = slot.remainPercent?.let { "$it %" } ?: "—"
      floats["$templateId.color.r"] = slot.color.red
      floats["$templateId.color.g"] = slot.color.green
      floats["$templateId.color.b"] = slot.color.blue
      floats["$templateId.color.a"] = slot.color.alpha
    }
    return CardSnapshotBindings(strings, emptyMap(), emptyMap(), floats)
  }

  private val controls = listOf("pause_printing", "resume_printing", "stop_printing")

  fun controlProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    val data = buildPrintControlData(card, snapshot) ?: return null
    return DesignProperties.build {
      enum("pause", if (data.pause != null) 1 else 0)
      enum("resume", if (data.resume != null) 1 else 0)
      enum("stop", if (data.stop != null) 1 else 0)
    }
  }

  fun controlTemplate(cardType: String, properties: DesignProperties): DesignTemplateInput? {
    if (properties["pause"] == null) return null
    val states = buildList {
      add(sensor("print_status", "printing"))
      for ((control, key) in controls.zip(listOf("pause", "resume", "stop"))) {
        if (properties[key] == "1") {
          val id = "button.${PREFIX}_$control"
          add(CardDesignSlots.templateEntity(id, "unknown"))
        }
      }
    }
    return template(cardType, states)
  }

  fun controlBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    val data = buildPrintControlData(card, snapshot) ?: return NONE
    return CardSnapshotBindings(
      mapOf("card.printer" to data.printerName),
      emptyMap(),
      emptyMap(),
      emptyMap(),
    )
  }

  /** A control tap: the template's button, pressed on the client's printer — that button only. */
  fun controlAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    action: ee.schimke.ha.rc.components.HaAction,
  ): ee.schimke.ha.rc.components.HaAction? {
    snapshot ?: return null
    val pressed =
      (action as? ee.schimke.ha.rc.components.HaAction.CallService)?.entityId ?: return null
    val control = controls.firstOrNull { pressed == "button.${PREFIX}_$it" } ?: return null
    val data = buildPrintControlData(card, snapshot) ?: return null
    return when (control) {
      "pause_printing" -> data.pause
      "resume_printing" -> data.resume
      else -> data.stop
    }?.tapAction
  }
}

/**
 * [this] tray / sensor id of [prefix]'s printer, renamed onto the template's [BambuDesign.PREFIX].
 */
private fun String.renamePrefix(prefix: String): String =
  replaceFirst(".${prefix}_", ".${BambuDesign.PREFIX}_")

/** What a tray's material line shows. */
private fun slotMaterial(slot: ee.schimke.ha.rc.components.HaBambuSpoolSlot): String =
  slot.materialText
