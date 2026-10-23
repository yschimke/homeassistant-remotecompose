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
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import ee.schimke.ha.rc.LocalFixedCanvasAxis
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardEntityIds
import ee.schimke.ha.rc.components.HaBambuPrintPhase
import ee.schimke.ha.rc.components.HaType
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.cardChrome
import ee.schimke.ha.rc.components.currentRemoteHaTheme
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.formatValueWithUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * Every card finds its printer with [resolveBambuPrinter]: the configured device through the
 * registry, else the only printer there is. With several printers and no way to tell which one a
 * card means, a card says so ("Choose a printer") and never shows, or acts on, a guessed printer.
 *
 * The base [Render] is the chrome-only fallback: variant label, printer, state.
 */
open class BambuLabCardConverter(
  final override val cardType: String,
  private val variantLabel: String,
) : CardConverter {

  // Signature for the chrome-only fallback render (variant label + printer + state) — baked, not
  // bound (see CardConverter.dataSignature). Which printer was resolved is part of it, so a change
  // of printer (the registry arriving, a second printer appearing) re-encodes.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String {
    val printer = resolveBambuPrinter(card, snapshot)
    val ids =
      printer.prefix?.let(::printStatusEntityIds)
        ?: setOfNotNull(card.raw["entity"]?.jsonPrimitive?.contentOrNull).takeUnless {
          printer.ambiguous
        }
        ?: emptySet()
    return "${printer.signature}|${cardDataSignature(ids, snapshot)}"
  }

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 96

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val theme = currentRemoteHaTheme()
    val printer = resolveBambuPrinter(card, snapshot)
    val prefix = printer.prefix
    // A configured `entity:` (never `printer:`, which is a device id, not an entity).
    val entity =
      card.raw["entity"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.let { snapshot.states[it] }
        .takeIf { prefix == null && !printer.ambiguous }
    val name =
      when {
        printer.ambiguous -> "Choose a printer"
        prefix != null -> bambuPrinterName(prefix, snapshot)
        else ->
          entity?.attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull
            ?: entity?.entityId
            ?: "Bambu Lab printer"
      }
    val state =
      when {
        printer.ambiguous -> ""
        prefix != null -> buildPrintStatusData(prefix, snapshot).phase.label
        else -> formatState(entity, snapshot)
      }

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
        // The name takes what the state leaves and ellipsizes; the state keeps its natural width on
        // one line. Unconstrained, a narrow Wear cell broke "Unavailable" mid-word. The gap is the
        // state's start padding, not spacedBy: a row hands its full width to a weighted child
        // without subtracting spacedBy gaps.
        RemoteRow(
          modifier = RemoteModifier.fillMaxWidth(),
          verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
          RemoteText(
            text = name.rs,
            modifier = RemoteModifier.weight(1f),
            color = theme.primaryText,
            fontSize = HaType.Body,
            style = RemoteTextStyle.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          RemoteText(
            text = LiveValues.state(entity?.entityId, state),
            modifier = RemoteModifier.padding(start = 8.rdp),
            color = theme.primaryText,
            fontSize = HaType.Body,
            style = RemoteTextStyle.Default,
            maxLines = 1,
          )
        }
        if (printer.ambiguous) {
          RemoteText(
            text =
              "${printer.candidates.size} printers here, and this card's printer isn't known".rs,
            color = theme.secondaryText,
            fontSize = HaType.Caption,
            style = RemoteTextStyle.Default,
          )
        }
      }
    }
  }
}

/**
 * AMS spool grid: the tray entities `sensor.<prefix>_ams_<n>_tray_<m>`. The card config's `ams:`
 * device id names the AMS through the registry, which gives both the printer and `n`; without it,
 * the only printer's first AMS (see [resolveBambuPrinter]).
 */
class BambuLabAmsCardConverter :
  BambuLabCardConverter(cardType = "custom:ha-bambulab-ams-card", variantLabel = "AMS") {
  /**
   * An AMS grid renders from a card design: which trays exist, which is active and whether each
   * reports what remains; materials, remaining labels and spool colours are bindings. The template
   * names its trays with [BambuDesign.PREFIX]; the client renames its printer's onto it.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    val printer = resolveBambuPrinter(card, snapshot)
    val prefix = printer.prefix ?: return null
    val slots = readSpoolSlots(prefix, snapshot, printer.amsIndex)
    if (slots.isEmpty()) return null
    return DesignProperties.build {
      slots.forEach { slot ->
        val tray = trayNumber(slot)
        enum("t$tray", if (slot.active) 2 else 1)
        enum("t$tray.remain", if (slot.remainPercent != null) 1 else 0)
      }
    }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    BambuDesign.amsTemplate(cardType, properties)

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    val printer = resolveBambuPrinter(card, snapshot)
    val prefix = printer.prefix ?: return BambuDesign.NONE
    val slots = readSpoolSlots(prefix, snapshot, printer.amsIndex)
    // The template's trays are AMS 1's, whichever AMS this card shows.
    return BambuDesign.slotBindings(slots.associateWith { BambuDesign.trayId(trayNumber(it)) })
  }

  // Renders discovered `sensor.<prefix>_ams_<n>_tray_*`, not the configured device — base over the
  // discovered trays (and which printer and AMS they are) so live tray changes re-encode.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String {
    val printer = resolveBambuPrinter(card, snapshot)
    val prefix = printer.prefix ?: return super.dataSignature(card, snapshot)
    return "${printer.signature}|" +
      cardDataSignature(amsTrayEntityIds(prefix, printer.amsIndex), snapshot)
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val printer = resolveBambuPrinter(card, snapshot)
    val prefix = printer.prefix
    if (prefix == null) {
      super.Render(card, snapshot, modifier)
      return
    }
    val slots = readSpoolSlots(prefix, snapshot, printer.amsIndex)
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
 * Single-spool detail. The card config's `spool:` names the spool: its entity (a tray, or the
 * external spool) directly, or its device through the registry. Without either we surface the
 * printer's active tray (or tray 1 if none is active); `slot:` pins a tray.
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

  internal fun chosenSlot(
    card: CardConfig,
    snapshot: HaSnapshot,
  ): ee.schimke.ha.rc.components.HaBambuSpoolSlot? {
    val printer = resolveBambuPrinter(card, snapshot)
    val prefix = printer.prefix ?: return null
    // The spool the config names (its entity, or its device's spool entities).
    val named = printer.deviceEntities.sorted().mapNotNull { spoolSlot(it, snapshot) }
    if (named.isNotEmpty()) return named.firstOrNull { it.active } ?: named.first()
    val slots = readSpoolSlots(prefix, snapshot, printer.amsIndex)
    val pinnedSlot = card.raw["slot"]?.jsonPrimitive?.content?.toIntOrNull()
    return pinnedSlot?.let { idx -> slots.getOrNull(idx - 1) }
      ?: slots.firstOrNull { it.active }
      ?: slots.firstOrNull()
  }

  // Renders one of the discovered spool slots — base over the whole tray set and the named spool so
  // a change to any (or to which is active) re-encodes, and over which printer it is.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String {
    val printer = resolveBambuPrinter(card, snapshot)
    val prefix = printer.prefix ?: return super.dataSignature(card, snapshot)
    val ids = amsTrayEntityIds(prefix, printer.amsIndex) + printer.deviceEntities
    return "${printer.signature}|${cardDataSignature(ids, snapshot)}"
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val slot = chosenSlot(card, snapshot)
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
  return (1..4).mapNotNull { trayIdx ->
    spoolSlot("sensor.${prefix}_ams_${amsIndex}_tray_$trayIdx", snapshot)
  }
}

/**
 * How full a tray's spool is, 0..100, or null when it isn't known: the integration reports `-1` for
 * a spool the AMS can't estimate (a third-party spool without an RFID tag), and nothing when
 * `remain_enabled` is off. Unknown draws "—", never "-1 %".
 */
internal fun bambuRemainPercent(attrs: Map<String, JsonElement>): Int? {
  if (attrs["remain_enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == false) return null
  return attrs["remain"]
    ?.jsonPrimitive
    ?.content
    ?.toDoubleOrNull()
    ?.takeIf { it in 0.0..100.0 }
    ?.toInt()
}

/** The tray's number within its AMS (`…_tray_3` → 3). */
private fun trayNumber(slot: ee.schimke.ha.rc.components.HaBambuSpoolSlot): Int =
  slot.entityId!!.substringAfterLast('_').toInt()

/**
 * The spool [entityId] holds — an AMS tray (`sensor.<prefix>_ams_<n>_tray_<m>`, "Slot m") or the
 * external spool (`sensor.<prefix>_externalspool_external_spool`) — or null for any other entity.
 */
private fun spoolSlot(
  entityId: String,
  snapshot: HaSnapshot,
): ee.schimke.ha.rc.components.HaBambuSpoolSlot? {
  val label =
    TrayEntity.matchEntire(entityId)?.let { "Slot ${it.groupValues[3]}" }
      ?: "External spool".takeIf { entityId.startsWith("sensor.") && entityId.endsWith(EXTERNAL) }
      ?: return null
  val entity = snapshot.states[entityId] ?: return null
  val attrs = entity.attributes
  val material = entity.state.takeUnless { it == "unknown" || it == "unavailable" } ?: "Empty"
  val color = parseHexArgb(attrs["color"]?.jsonPrimitive?.content)
  val remain = bambuRemainPercent(attrs)
  val active = attrs["active"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
  return ee.schimke.ha.rc.components.HaBambuSpoolSlot(
    entityId = entity.entityId,
    slotLabel = label,
    material = LiveValues.state(entity.entityId, material),
    color = color,
    remainPercent = remain,
    active = active,
    materialText = material,
  )
}

/** `sensor.<prefix>_ams_<n>_tray_<m>`. */
private val TrayEntity = Regex("""sensor\.(.+)_ams_(\d+)_tray_(\d+)""")

/** The external spool sensor's suffix. */
private const val EXTERNAL = "_externalspool_external_spool"

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
 * config's `printer:` device id finds the prefix through the registry ([resolveBambuPrinter]), and
 * related sensors are read by suffix.
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
    val prefix = resolveBambuPrinter(card, snapshot).prefix ?: return null
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
    val prefix = resolveBambuPrinter(card, snapshot).prefix ?: return BambuDesign.NONE
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
  // configured printer device — base over those (and which printer) so live print data re-encodes.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String {
    val printer = resolveBambuPrinter(card, snapshot)
    val prefix = printer.prefix ?: return super.dataSignature(card, snapshot)
    return "${printer.signature}|${cardDataSignature(printStatusEntityIds(prefix), snapshot)}"
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val prefix = resolveBambuPrinter(card, snapshot).prefix
    if (prefix == null) {
      super.Render(card, snapshot, modifier)
      return
    }
    val data = buildPrintStatusData(prefix, snapshot)
    when (LocalCardSizeMode.current) {
      // Dashboard: the two-column layout, so the job's lines use the right half.
      CardSizeMode.Wrap -> ee.schimke.ha.rc.components.RemoteHaBambuPrintStatus(data, modifier)
      CardSizeMode.Fixed ->
        if (LocalFixedCanvasAxis.current == BreakpointAxis.Height) {
          // A Wear slot: one height gate between its Small and Large containers, neither tall
          // enough for the launcher column (card-information-hierarchy.md).
          RemoteSizeBreakpoint(
            thresholdsDp = intArrayOf(WEAR_SMALL_MAX_HEIGHT_DP),
            modifier = modifier.fillMaxSize(),
            axis = BreakpointAxis.Height,
          ) { tier ->
            CenteredCell {
              ee.schimke.ha.rc.components.RemoteHaBambuPrintStatusWear(
                data,
                RemoteModifier.fillMaxWidth(),
                large = tier > 0,
              )
            }
          }
        } else {
          // Launcher: a narrow cell stacks the same content in a column; a wide one lays it out
          // in two. One width gate (#224).
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
}

/** Width (dp) from which the print status lays its content out in two columns. */
private const val PRINT_STATUS_WIDE_MIN_DP = 260

/**
 * Which printer a Bambu Lab card shows. [prefix] is its entity prefix (`p2s_sn2` for
 * `sensor.p2s_sn2_print_progress`), null when it can't be told; [candidates] every printer the
 * snapshot has; [deviceEntities] the entities of the device the config names, when the registry (or
 * the config itself) named them: an AMS's trays, a spool.
 */
internal data class BambuPrinter(
  val prefix: String?,
  val candidates: List<String>,
  val deviceEntities: Set<String> = emptySet(),
) {
  /** Several printers and nothing to tell which one the card means: the card must not guess. */
  val ambiguous: Boolean
    get() = prefix == null && candidates.size > 1

  /** The AMS the configured device is (`…_ams_2_tray_1` → 2), else the first. */
  val amsIndex: Int
    get() =
      deviceEntities
        .firstNotNullOfOrNull { TrayEntity.matchEntire(it)?.groupValues?.get(2) }
        ?.toIntOrNull() ?: 1

  /** What the resolution decided, for data signatures: a change of printer re-encodes. */
  val signature: String
    get() = prefix?.let { "printer=$it@$amsIndex" } ?: "printer?${candidates.joinToString(",")}"
}

/**
 * The printer [card] shows, in order:
 * 1. an explicit `entity_prefix:` (design templates, and an override for any card);
 * 2. the configured device — `printer:` (print status / control), `ams:` or `spool:` — through the
 *    registry: the printer whose entities belong to that device id. `spool:` may name the spool's
 *    entity instead;
 * 3. the card's own entities (`entity:`, `entities:`), when they are one printer's;
 * 4. the only printer, when there is exactly one.
 *
 * Otherwise the prefix is null: with no printer the card falls back to its chrome, and with several
 * it is [BambuPrinter.ambiguous]. It never picks one of several printers by position.
 */
internal fun resolveBambuPrinter(card: CardConfig, snapshot: HaSnapshot): BambuPrinter {
  val candidates = bambuPrinters(snapshot)
  fun printerOf(entityId: String): String? {
    val objectId = entityId.substringAfter('.', "")
    return candidates.filter { objectId.startsWith("${it}_") }.maxByOrNull { it.length }
  }
  card.raw["entity_prefix"]
    ?.jsonPrimitive
    ?.contentOrNull
    ?.takeIf { it.isNotEmpty() }
    ?.let { prefix ->
      // An `ams:` / `spool:` naming one of this printer's entities still says which AMS or spool.
      val named =
        DeviceKeys.mapNotNull { key ->
            (card.raw[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf {
              it in snapshot.states && it.substringAfter('.', "").startsWith("${prefix}_")
            }
          }
          .toSet()
      return BambuPrinter(prefix, candidates, named)
    }
  for (key in DeviceKeys) {
    val configured = card.raw[key]?.jsonPrimitive?.contentOrNull?.trim()
    if (configured.isNullOrEmpty()) continue
    // `spool:` may be the spool's entity id.
    if (configured in snapshot.states) {
      printerOf(configured)?.let {
        return BambuPrinter(it, candidates, setOf(configured))
      }
    }
    val owned = snapshot.devices.filterValues { it.deviceId == configured }.keys
    val printers = owned.mapNotNull(::printerOf).toSet()
    if (printers.size == 1) return BambuPrinter(printers.single(), candidates, owned)
  }
  val own = cardEntityIds(card).mapNotNull(::printerOf).toSet()
  if (own.size == 1) return BambuPrinter(own.single(), candidates)
  return BambuPrinter(candidates.singleOrNull(), candidates)
}

/** The config keys that name a Bambu card's device. */
private val DeviceKeys = listOf("printer", "ams", "spool")

/** The Bambu Lab cards' types: every card [resolveBambuPrinter] finds a printer for. */
private val BambuCardTypes =
  setOf(
    "custom:ha-bambulab-print_status-card",
    "custom:ha-bambulab-print_control-card",
    "custom:ha-bambulab-skipobject-card",
    "custom:ha-bambulab-ams-card",
    "custom:ha-bambulab-spool-card",
  )

/**
 * [card] with every Bambu Lab card in it (stacks and grids included) carrying the printer its
 * configured device resolves to, for a host that renders it from a snapshot without the device
 * registry: a Wear widget or tile, whose values carry entity states only.
 *
 * A Bambu card names its printer, AMS or spool by device id (`printer:`, `ams:`, `spool:`), and
 * only [HaSnapshot.devices] says which entities that device has. Resolved here, on the side that
 * has the registry, the card gains the explicit `entity_prefix:` step 1 of [resolveBambuPrinter]
 * honours, and an `ams:` / `spool:` device id becomes that device's own tray or spool entity (which
 * AMS, which spool) — so the reduced host shows and controls the same printer, without guessing
 * among several.
 *
 * A card that already resolves the same without the registry (an explicit prefix, its own entities,
 * the only printer) or that the registry can't resolve either is returned unchanged.
 */
fun resolveBambuDevices(card: CardConfig, snapshot: HaSnapshot): CardConfig {
  if (snapshot.devices.isEmpty()) return card
  val raw = resolveBambuDevices(card.raw, snapshot)
  return if (raw == card.raw) card else card.copy(raw = raw)
}

private fun resolveBambuDevices(raw: JsonObject, snapshot: HaSnapshot): JsonObject {
  val type = (raw["type"] as? JsonPrimitive)?.contentOrNull
  var out =
    if (type != null && type in BambuCardTypes) pinBambuDevice(CardConfig(type, raw), snapshot)
    else raw
  // Stacks and grids: their cards are rendered from the same reduced snapshot.
  (out["cards"] as? JsonArray)?.let { cards ->
    val resolved =
      JsonArray(
        cards.map { (it as? JsonObject)?.let { c -> resolveBambuDevices(c, snapshot) } ?: it }
      )
    if (resolved != cards) out = JsonObject(out + ("cards" to resolved))
  }
  (out["card"] as? JsonObject)?.let { inner ->
    val resolved = resolveBambuDevices(inner, snapshot)
    if (resolved != inner) out = JsonObject(out + ("card" to resolved))
  }
  return out
}

/**
 * The entities a host rendering [card] from part of [snapshot] needs for its Bambu Lab cards
 * (stacks and grids included): every entity of every printer in [snapshot], since a card's printer
 * can be resolved from those entities alone ([resolveBambuPrinter]: an `entity_prefix:`, its own
 * entities, or the only printer) and its readings and controls are named from the printer's prefix.
 * Empty when [card] holds no Bambu card.
 */
fun bambuEntityIds(card: CardConfig, snapshot: HaSnapshot): Set<String> {
  if (!hasBambuCard(card.raw)) return emptySet()
  val printers = bambuPrinters(snapshot)
  return snapshot.states.keys
    .filter { id ->
      val objectId = id.substringAfter('.', "")
      printers.any { objectId.startsWith("${it}_") }
    }
    .toSet()
}

private fun hasBambuCard(raw: JsonObject): Boolean =
  (raw["type"] as? JsonPrimitive)?.contentOrNull in BambuCardTypes ||
    (raw["cards"] as? JsonArray).orEmpty().any { it is JsonObject && hasBambuCard(it) } ||
    (raw["card"] as? JsonObject)?.let(::hasBambuCard) == true

/** One Bambu card's config with its registry-resolved printer made explicit; see above. */
private fun pinBambuDevice(card: CardConfig, snapshot: HaSnapshot): JsonObject {
  val printer = resolveBambuPrinter(card, snapshot)
  val prefix = printer.prefix ?: return card.raw
  val reduced = snapshot.copy(devices = emptyMap())
  if (resolveBambuPrinter(card, reduced).sameAs(printer)) return card.raw
  // The device's own tray / spool entity: which AMS an AMS card shows, which spool a spool card.
  val deviceEntity = printer.spoolEntities.minOrNull()
  val pinned = buildMap {
    putAll(card.raw)
    put("entity_prefix", JsonPrimitive(prefix))
    if (deviceEntity != null) {
      for (key in listOf("ams", "spool")) {
        val configured = (card.raw[key] as? JsonPrimitive)?.contentOrNull?.trim()
        if (!configured.isNullOrEmpty() && configured !in snapshot.states) {
          put(key, JsonPrimitive(deviceEntity))
        }
      }
    }
  }
  return JsonObject(pinned)
}

/** The same printer, AMS and spool: what a card renders from the resolution. */
private fun BambuPrinter.sameAs(other: BambuPrinter): Boolean =
  prefix == other.prefix && amsIndex == other.amsIndex && spoolEntities == other.spoolEntities

/** The configured device's tray and spool entities. */
private val BambuPrinter.spoolEntities: Set<String>
  get() =
    deviceEntities
      .filter { TrayEntity.matches(it) || (it.startsWith("sensor.") && it.endsWith(EXTERNAL)) }
      .toSet()

/**
 * Every Bambu printer in [snapshot], by entity prefix: whatever has a print progress, status or
 * stage sensor, an AMS tray or a print button. An entity the registry says another integration
 * provides is not a Bambu printer's.
 */
internal fun bambuPrinters(snapshot: HaSnapshot): List<String> {
  val prefixes = sortedSetOf<String>()
  for (id in snapshot.states.keys) {
    val integration = snapshot.devices[id]?.integration
    if (integration != null && integration != BAMBU_INTEGRATION) continue
    val prefix =
      PrinterSignals.firstNotNullOfOrNull { (domain, suffix) ->
        id
          .takeIf { it.startsWith(domain) && it.endsWith(suffix) }
          ?.removePrefix(domain)
          ?.removeSuffix(suffix)
      } ?: TrayEntity.matchEntire(id)?.groupValues?.get(1)
    if (!prefix.isNullOrEmpty()) prefixes += prefix
  }
  return prefixes.toList()
}

/** The ha-bambulab integration's domain. */
private const val BAMBU_INTEGRATION = "bambu_lab"

/** `domain.` and suffix of the entities that mark a printer. */
private val PrinterSignals =
  listOf(
    "sensor." to "_print_progress",
    "sensor." to "_print_status",
    "sensor." to "_current_stage",
    "button." to "_pause_printing",
    "button." to "_resume_printing",
    "button." to "_stop_printing",
  )

/** Tray sensors the AMS / spool render paths read (`sensor.<prefix>_ams_<n>_tray_1..4`). */
private fun amsTrayEntityIds(prefix: String, amsIndex: Int): Set<String> =
  (1..4).map { "sensor.${prefix}_ams_${amsIndex}_tray_$it" }.toSet()

/** Sensors [buildPrintStatusData] reads for the print-status card. */
private fun printStatusEntityIds(prefix: String): Set<String> = buildSet {
  add("sensor.${prefix}_print_progress")
  add("sensor.${prefix}_current_stage")
  add("sensor.${prefix}_print_status")
  add("sensor.${prefix}_current_layer")
  add("sensor.${prefix}_total_layer_count")
  add("sensor.${prefix}_remaining_time")
  add("sensor.${prefix}_nozzle_temperature")
  NozzleTargets.forEach { add("sensor.${prefix}_$it") }
  add("sensor.${prefix}_bed_temperature")
  BedTargets.forEach { add("sensor.${prefix}_$it") }
  add("sensor.${prefix}_printer_name")
  add("binary_sensor.${prefix}_online")
}

/** Buttons, phase and name sensors [buildPrintControlData] reads for the control pad. */
private fun printControlEntityIds(prefix: String): Set<String> = buildSet {
  add("button.${prefix}_pause_printing")
  add("button.${prefix}_resume_printing")
  add("button.${prefix}_stop_printing")
  // What decides the phase, so pausing or finishing re-encodes which controls show.
  add("sensor.${prefix}_print_status")
  add("sensor.${prefix}_current_stage")
  add("sensor.${prefix}_print_progress")
  add("sensor.${prefix}_total_layer_count")
  add("binary_sensor.${prefix}_online")
  // Every source [bambuPrinterName] reads, so a renamed printer re-encodes the pad.
  add("sensor.${prefix}_printer_name")
  BambuNameSources.forEach { (suffix, _) -> add("sensor.${prefix}_$suffix") }
}

/**
 * The target-temperature sensors' suffixes. The integration names them `nozzle_target_temperature`
 * / `bed_target_temperature`; older releases (and some entity renames) `target_<part>_temperature`.
 */
private val NozzleTargets = listOf("nozzle_target_temperature", "target_nozzle_temperature")
private val BedTargets = listOf("bed_target_temperature", "target_bed_temperature")

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
  val nozzleTarget = NozzleTargets.firstNotNullOfOrNull { sensor(it)?.state?.toFloatOrNull() }
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
  val bedTarget = BedTargets.firstNotNullOfOrNull { sensor(it)?.state?.toFloatOrNull() }
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
internal val HaBambuPrintPhase.label: String
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

private fun formatTemp(value: Float): String = decimalText(value.toDouble(), 1)

/**
 * Pause / resume / cancel pad: the printer's button entities that apply now — Pause while printing,
 * Resume while paused, Stop in either — and a tap fires `button.press` on that entity. A button
 * whose entity is unavailable is shown greyed out and does nothing. Falls back to chrome-only (or
 * "Choose a printer") when the printer can't be told: it never offers another printer's buttons.
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

  // Renders discovered `button.<prefix>_*` controls, the phase + a name sensor, not the configured
  // printer device — base over those (and which printer) so availability changes re-encode.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    printControlSignature(card, snapshot) ?: super.dataSignature(card, snapshot)

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

  // Renders discovered `button.<prefix>_*` controls, the phase + a name sensor, not the configured
  // printer device — base over those (and which printer) so availability changes re-encode.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    printControlSignature(card, snapshot) ?: super.dataSignature(card, snapshot)

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

private fun printControlSignature(card: CardConfig, snapshot: HaSnapshot): String? {
  val printer = resolveBambuPrinter(card, snapshot)
  val prefix = printer.prefix ?: return null
  return "${printer.signature}|${cardDataSignature(printControlEntityIds(prefix), snapshot)}"
}

/** What a print's state lets the pad do. */
internal enum class BambuControlPhase {
  /** Printing (or preparing): Pause and Stop. */
  Running,
  /** Paused: Resume and Stop. */
  Paused,
  /** Idle, finished, failed or offline: nothing to control. */
  Stopped,
  /**
   * The printer reports no print state at all (no status, stage or progress sensor): every control
   * it has, each still gated on its own availability.
   */
  Unknown,
}

internal fun bambuControlPhase(prefix: String, snapshot: HaSnapshot): BambuControlPhase {
  val stateSensors = listOf("print_status", "current_stage", "print_progress")
  if (stateSensors.none { "sensor.${prefix}_$it" in snapshot.states }) {
    return BambuControlPhase.Unknown
  }
  val status = snapshot.states["sensor.${prefix}_print_status"]?.state?.trim()?.lowercase()
  if (status == "pause" || status == "paused") return BambuControlPhase.Paused
  return when (buildPrintStatusData(prefix, snapshot).phase) {
    HaBambuPrintPhase.Printing -> BambuControlPhase.Running
    else -> BambuControlPhase.Stopped
  }
}

/**
 * The pad for [card]'s printer, or null when the printer can't be told ([resolveBambuPrinter]).
 * Only the controls that apply to the print's [BambuControlPhase] are present; one whose entity is
 * `unavailable` is present but not enabled. With none, [HaBambuPrintControlData.statusLine] says
 * why.
 */
internal fun buildPrintControlData(
  card: CardConfig,
  snapshot: HaSnapshot,
): ee.schimke.ha.rc.components.HaBambuPrintControlData? {
  val prefix = resolveBambuPrinter(card, snapshot).prefix ?: return null
  val phase = bambuControlPhase(prefix, snapshot)

  fun control(
    suffix: String,
    appliesWhen: Set<BambuControlPhase>,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Long,
  ): ee.schimke.ha.rc.components.HaBambuControlButton? {
    if (phase !in appliesWhen) return null
    val entity = snapshot.states["button.${prefix}_$suffix"] ?: return null
    return ee.schimke.ha.rc.components.HaBambuControlButton(
      label = label,
      icon = icon,
      accent = androidx.compose.ui.graphics.Color(accent),
      tapAction =
        ee.schimke.ha.rc.components.HaAction.CallService(
          domain = "button",
          service = "press",
          entityId = entity.entityId,
          serviceData = kotlinx.serialization.json.JsonObject(emptyMap()),
        ),
      enabled = entity.state != "unavailable",
    )
  }

  val running = setOf(BambuControlPhase.Running, BambuControlPhase.Unknown)
  val paused = setOf(BambuControlPhase.Paused, BambuControlPhase.Unknown)
  val pause = control("pause_printing", running, "Pause", Icons.Filled.Pause, 0xFFFFA000)
  val resume = control("resume_printing", paused, "Resume", Icons.Filled.PlayArrow, 0xFF43A047)
  val stop = control("stop_printing", running + paused, "Stop", Icons.Filled.Stop, 0xFFE53935)
  val statusLine =
    if (pause != null || resume != null || stop != null) null
    else
      when (phase) {
        BambuControlPhase.Stopped ->
          when (buildPrintStatusData(prefix, snapshot).phase) {
            HaBambuPrintPhase.Finished -> "Print finished"
            HaBambuPrintPhase.Failed -> "Print failed"
            HaBambuPrintPhase.Offline -> "Printer offline"
            else -> "Nothing printing"
          }
        else -> "No controls for this printer"
      }
  return ee.schimke.ha.rc.components.HaBambuPrintControlData(
    printerName = bambuPrinterName(prefix, snapshot),
    pause = pause,
    resume = resume,
    stop = stop,
    statusLine = statusLine,
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

  /** A control's property: 0 not shown, 1 shown, 2 shown but unavailable. */
  private fun controlState(button: ee.schimke.ha.rc.components.HaBambuControlButton?): Int =
    when {
      button == null -> 0
      button.enabled -> 1
      else -> 2
    }

  fun controlProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    val data = buildPrintControlData(card, snapshot) ?: return null
    return DesignProperties.build {
      enum("pause", controlState(data.pause))
      enum("resume", controlState(data.resume))
      enum("stop", controlState(data.stop))
    }
  }

  fun controlTemplate(cardType: String, properties: DesignProperties): DesignTemplateInput? {
    val shown = listOf("pause", "resume", "stop").map { properties[it] ?: return null }
    val (pause, resume, stop) = shown
    // A print state under which exactly the shown controls apply (see buildPrintControlData): no
    // state at all when Pause and Resume both show.
    val status =
      when {
        pause != "0" && resume != "0" -> null
        resume != "0" -> "pause"
        pause != "0" || stop != "0" -> "running"
        else -> "idle"
      }
    val states = buildList {
      status?.let { add(sensor("print_status", it)) }
      for ((control, value) in controls.zip(shown)) {
        if (value == "0") continue
        val id = "button.${PREFIX}_$control"
        add(CardDesignSlots.templateEntity(id, if (value == "1") "unknown" else "unavailable"))
      }
    }
    return template(cardType, states)
  }

  fun controlBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    val data = buildPrintControlData(card, snapshot) ?: return NONE
    return CardSnapshotBindings(
      buildMap {
        put("card.printer", data.printerName)
        data.statusLine?.let { put("card.status", it) }
      },
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
    // Only a control that is shown and available acts.
    return when (control) {
        "pause_printing" -> data.pause
        "resume_printing" -> data.resume
        else -> data.stop
      }
      ?.takeIf { it.enabled }
      ?.tapAction
  }
}

/** What a tray's material line shows. */
private fun slotMaterial(slot: ee.schimke.ha.rc.components.HaBambuSpoolSlot): String =
  slot.materialText
