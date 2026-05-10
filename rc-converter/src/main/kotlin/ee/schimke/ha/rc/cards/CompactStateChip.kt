@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.LocalHaTheme
import ee.schimke.ha.rc.formatState
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tiny "chip" variant rendered by Fixed-mode card converters on narrow
 * surfaces. Drops the icon + entity name and centers the live state
 * value, color-coded via the theme's primary text role. Mirrors what
 * HA itself falls back to on cramped widget surfaces.
 *
 * Used by single-entity card converters (`tile`, `entity`, `button`,
 * `gauge`) when [ee.schimke.ha.rc.RemoteSizeBreakpoint] picks the
 * narrow tier.
 */
@Composable
internal fun CompactStateChip(card: CardConfig, snapshot: HaSnapshot) {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val theme = LocalHaTheme.current
    RemoteBox(modifier = RemoteModifier.fillMaxSize()) {
        RemoteText(
            text = LiveValues.state(entityId, formatState(entity)),
            color = theme.primaryText.rc,
        )
    }
}
