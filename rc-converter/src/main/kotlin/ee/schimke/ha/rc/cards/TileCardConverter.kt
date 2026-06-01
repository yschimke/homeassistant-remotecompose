@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.toTyped
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardWidthClass
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.components.HaTileData
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaIconChip
import ee.schimke.ha.rc.components.RemoteHaTile
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `tile` card — builds [HaTileData] from config + snapshot.
 *
 * In [CardSizeMode.Wrap] the card always renders the full HA-style tile.
 * In [CardSizeMode.Fixed] (launcher / wear widgets) the converter wraps
 * its output in a [RemoteSizeBreakpoint] icon-+-state ladder
 * (adaptive-card-layouts.md §"Icon + state row/tile"):
 *
 *   * **Narrow** (`w < 96 dp`, e.g. a `1×1` launcher cell) →
 *     [RemoteHaIconChip], the tinted state icon centred over a single
 *     state line. Keeps the icon identity at the smallest size instead
 *     of dropping to a bare text chip (Principle 1).
 *   * **Full** (otherwise) → the HA-reference [RemoteHaTile], wrapped in
 *     [CenteredCell] so a tall cell (`3×2`) centres the row rather than
 *     gluing it to the top over a blank half (Principles 7–8).
 */
class TileCardConverter : CardConverter {
    override val cardType: String = CardTypes.TILE

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 43

    override fun naturalWidthClass(card: CardConfig, snapshot: HaSnapshot): CardWidthClass =
        CardWidthClass.Compact

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        when (LocalCardSizeMode.current) {
            CardSizeMode.Wrap -> FullTile(card, snapshot, modifier)
            CardSizeMode.Fixed ->
                RemoteSizeBreakpoint(
                    thresholdsDp = intArrayOf(96),
                    modifier = modifier.fillMaxSize(),
                ) { tier ->
                    when (tier) {
                        0 -> RemoteHaIconChip(tileData(card, snapshot), RemoteModifier.fillMaxSize())
                        else -> CenteredCell { FullTile(card, snapshot, RemoteModifier.fillMaxWidth()) }
                    }
                }
        }
    }

    @Composable
    private fun FullTile(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        RemoteHaTile(tileData(card, snapshot), modifier = modifier)
    }

    @Composable
    private fun tileData(card: CardConfig, snapshot: HaSnapshot): HaTileData {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }
        val name =
            card.raw["name"]?.jsonPrimitive?.content
                ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
                ?: entityId
                ?: "(no entity)"
        val tapCfg = card.raw["tap_action"]?.jsonObject
        val tapAction =
            if (tapCfg != null) parseHaAction(tapCfg, entityId) else defaultTapActionFor(entityId)
        val isActive = entity?.toTyped()?.isActive

        return HaTileData(
            entityId = entityId,
            name = name,
            state = LiveValues.state(entityId, formatState(entity)),
            icon = HaIconMap.resolve(card.raw["icon"]?.jsonPrimitive?.content, entity),
            accent =
                HaToggleAccent(
                    activeAccent = HaStateColor.activeFor(entity).rc,
                    inactiveAccent = HaStateColor.inactiveFor(entity).rc,
                    initiallyOn = isActive ?: false,
                    toggleable = isActive != null,
                ),
            tapAction = tapAction,
        )
    }
}

