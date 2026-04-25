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

/** Current print job + progress. */
class BambuLabPrintStatusCardConverter : BambuLabCardConverter(
    cardType = "custom:ha-bambulab-print_status-card",
    variantLabel = "Print status",
)

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
