@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.components.LocalHaTheme
import ee.schimke.ha.rc.formatState
import kotlinx.serialization.json.jsonPrimitive

/**
 * One converter per HACS card from
 * [`greghesp/ha-bambulab-cards`](https://github.com/greghesp/ha-bambulab-cards).
 *
 * Card-name reference (from `src/cards/<name>/const.ts` with
 * `PREFIX_NAME = "ha-bambulab"`):
 * - `custom:ha-bambulab-ams-card` — AMS spool grid
 * - `custom:ha-bambulab-spool-card` — single spool detail
 * - `custom:ha-bambulab-print_status-card` — current print job + progress
 * - `custom:ha-bambulab-print_control-card` — pause / resume / cancel pad
 *   (legacy alias `custom:ha-bambulab-skipobject-card`)
 *
 * Visual reference: card screenshots live on the integration's docs site
 * (`docs.page/greghesp/ha-bambulab`) — no inline assets in the cards repo.
 *
 * Today every variant renders the same chrome (variant label + the
 * configured printer entity + state). Override [Render] in a subclass
 * once the per-variant visuals (AMS grid, progress ring, control pad)
 * are built.
 */
open class BambuLabCardConverter(
    final override val cardType: String,
    private val variantLabel: String,
) : CardConverter {

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 96

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val theme = LocalHaTheme.current
        val entityId = card.raw["printer"]?.jsonPrimitive?.content
            ?: card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }
        val name = entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
            ?: entityId
            ?: "Bambu Lab printer"
        val state = formatState(entity)

        RemoteBox(
            modifier = modifier
                .fillMaxWidth()
                .clip(RemoteRoundedCornerShape(12.rdp))
                .background(theme.cardBackground.rc)
                .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
                .padding(horizontal = 12.rdp, vertical = 10.rdp),
        ) {
            RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(4.rdp)) {
                RemoteText(
                    text = "Bambu Lab".rs,
                    color = theme.secondaryText.rc,
                    fontSize = 11.rsp,
                    style = RemoteTextStyle.Default,
                )
                RemoteText(
                    text = variantLabel.rs,
                    color = theme.primaryText.rc,
                    fontSize = 15.rsp,
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
                        color = theme.primaryText.rc,
                        fontSize = 13.rsp,
                        style = RemoteTextStyle.Default,
                    )
                    RemoteText(
                        text = state.rs,
                        color = theme.secondaryText.rc,
                        fontSize = 13.rsp,
                        style = RemoteTextStyle.Default,
                    )
                }
            }
        }
    }
}

/** AMS spool grid. */
class BambuLabAmsCardConverter : BambuLabCardConverter(
    cardType = "custom:ha-bambulab-ams-card",
    variantLabel = "AMS",
)

/** Single-spool detail. */
class BambuLabSpoolCardConverter : BambuLabCardConverter(
    cardType = "custom:ha-bambulab-spool-card",
    variantLabel = "Spool",
)

/** Current print job + progress.
 *
 *  HA's bambulab integration exposes a flock of `sensor.<prefix>_*`
 *  entities per printer; the card config references a device id that we
 *  can't resolve without a device-registry channel. We discover the
 *  prefix by scanning the snapshot for the first `sensor.*_print_progress`
 *  and pull related sensors by suffix. Configurations with multiple
 *  printers can override via an `entity_prefix:` config field. */
class BambuLabPrintStatusCardConverter : BambuLabCardConverter(
    cardType = "custom:ha-bambulab-print_status-card",
    variantLabel = "Print status",
) {
    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val prefix = resolvePrinterPrefix(card, snapshot)
        if (prefix == null) {
            super.Render(card, snapshot, modifier)
            return
        }
        val data = buildPrintStatusData(prefix, snapshot)
        ee.schimke.ha.rc.components.RemoteHaBambuPrintStatus(data, modifier)
    }
}

/** Best-effort discovery: explicit `entity_prefix` wins; otherwise the
 *  first `sensor.*_print_progress` entity's prefix. Returns null when
 *  the snapshot has no Bambu-shaped entities, in which case the caller
 *  falls back to the chrome-only renderer. */
internal fun resolvePrinterPrefix(card: CardConfig, snapshot: HaSnapshot): String? {
    val explicit = card.raw["entity_prefix"]?.jsonPrimitive?.content
    if (!explicit.isNullOrEmpty()) return explicit
    return snapshot.states.keys
        .firstOrNull { it.startsWith("sensor.") && it.endsWith("_print_progress") }
        ?.removePrefix("sensor.")
        ?.removeSuffix("_print_progress")
}

private fun buildPrintStatusData(
    prefix: String,
    snapshot: HaSnapshot,
): ee.schimke.ha.rc.components.HaBambuPrintStatusData {
    val s = snapshot.states
    fun sensor(suffix: String) = s["sensor.${prefix}_$suffix"]

    val progress = sensor("print_progress")?.state?.toFloatOrNull() ?: 0f
    val stageRaw = sensor("current_stage")?.state ?: sensor("print_status")?.state ?: "idle"
    val stage = stageRaw.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

    val printerEntity = sensor("print_progress") ?: sensor("print_status")
    val printerName = printerEntity?.attributes?.get("friendly_name")
        ?.jsonPrimitive?.content
        ?.substringBeforeLast(' ')
        ?: prefix.uppercase()

    val current = sensor("current_layer")?.state?.toIntOrNull()
    val total = sensor("total_layer_count")?.state?.toIntOrNull()
    val layerLine = if (current != null && total != null) "Layer $current / $total" else null

    val remaining = sensor("remaining_time")?.state?.toIntOrNull()
    val remainingLine = remaining?.let { mins ->
        val h = mins / 60
        val m = mins % 60
        if (h > 0) "${h}h ${m}m left" else "${m}m left"
    }

    val nozzle = sensor("nozzle_temperature")?.state?.toFloatOrNull()
    val nozzleTarget = sensor("target_nozzle_temperature")?.state?.toFloatOrNull()
    val nozzleLine = nozzle?.let {
        val tgt = nozzleTarget?.let { t -> if (t > 0f) " → ${formatTemp(t)}°C" else "" } ?: ""
        "Nozzle ${formatTemp(it)}°C$tgt"
    }

    val bed = sensor("bed_temperature")?.state?.toFloatOrNull()
    val bedTarget = sensor("target_bed_temperature")?.state?.toFloatOrNull()
    val bedLine = bed?.let {
        val tgt = bedTarget?.let { t -> if (t > 0f) " → ${formatTemp(t)}°C" else "" } ?: ""
        "Bed ${formatTemp(it)}°C$tgt"
    }

    return ee.schimke.ha.rc.components.HaBambuPrintStatusData(
        printerName = printerName.rs,
        stage = stage.rs,
        progressLabel = "${progress.toInt()} %".rs,
        progressFraction = (progress / 100f).coerceIn(0f, 1f),
        accent = androidx.compose.ui.graphics.Color(0xFFFF8F00),
        layerLine = layerLine?.rs,
        remainingLine = remainingLine?.rs,
        nozzleLine = nozzleLine?.rs,
        bedLine = bedLine?.rs,
    )
}

private fun formatTemp(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)

/** Pause / resume / cancel pad. */
class BambuLabPrintControlCardConverter : BambuLabCardConverter(
    cardType = "custom:ha-bambulab-print_control-card",
    variantLabel = "Print control",
)

/** Legacy alias of print_control card from earlier versions. */
class BambuLabSkipObjectCardConverter : BambuLabCardConverter(
    cardType = "custom:ha-bambulab-skipobject-card",
    variantLabel = "Print control",
)
