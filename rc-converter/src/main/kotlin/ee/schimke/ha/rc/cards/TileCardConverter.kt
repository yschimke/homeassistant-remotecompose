package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import kotlinx.serialization.json.jsonPrimitive

/**
 * First-pass converter for the `tile` card — HA's modern default primary card.
 *
 * Visual target (see `home-assistant/frontend` → `hui-tile-card.ts`):
 *
 * ```
 *   ┌──────────────────────────────────┐
 *   │  [●] Name                        │
 *   │      State · unit                │
 *   └──────────────────────────────────┘
 * ```
 *
 * Only the primary-info layout is covered here; icon color, tap actions, and
 * the `features:` row (light-brightness, cover-position, etc.) come later.
 */
class TileCardConverter : CardConverter {
    override val cardType: String = CardTypes.TILE

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot) {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }

        val name = card.raw["name"]?.jsonPrimitive?.content
            ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
            ?: entityId
            ?: "(no entity)"
        val stateText = entity?.state ?: "unavailable"
        val unit = entity?.attributes?.get("unit_of_measurement")?.jsonPrimitive?.content
        val displayState = if (unit != null) "$stateText $unit" else stateText

        RemoteBox(
            modifier = RemoteModifier
                .clip(RemoteRoundedCornerShape(12.rdp))
                .background(Color(0xFF1F2329).rc)
                .padding(12.rdp),
        ) {
            RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
                RemoteBox(
                    modifier = RemoteModifier
                        .clip(RemoteRoundedCornerShape(20.rdp))
                        .background(Color(0xFF2A2F36).rc)
                        .padding(8.rdp),
                )
                RemoteColumn(modifier = RemoteModifier.padding(left = 12.rdp)) {
                    RemoteText(
                        text = name.rs,
                        color = Color.White.rc,
                        fontSize = 14.rsp,
                        fontWeight = FontWeight.Medium,
                        style = RemoteTextStyle.Default,
                    )
                    RemoteText(
                        text = displayState.rs,
                        color = Color(0xFFB3B9C1).rc,
                        fontSize = 12.rsp,
                        style = RemoteTextStyle.Default,
                    )
                }
            }
        }
    }
}
