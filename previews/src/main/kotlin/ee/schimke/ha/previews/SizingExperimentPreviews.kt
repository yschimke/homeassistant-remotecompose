@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth as rcFillMaxWidth
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.LocalRcDebugBorders
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.cardHeightDp
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme

/**
 * Experiments that visualise how a RemoteCompose card document sizes
 * itself inside its host slot. With `LocalRcDebugBorders` provided as
 * `true`:
 *
 *  - the host `Box` draws a red 1.dp border (Compose UI side)
 *  - the captured document draws a blue 1.rdp border around an outer
 *    wrapper that follows its intrinsic content height (RemoteCompose
 *    side, see [DebugRcBorderWrapper])
 *
 * Mismatch readings:
 *
 *  - **Exact**: red and blue coincide → host slot height matches the
 *    document's content height.
 *  - **Slack** (red taller than blue): host slot is taller than the
 *    document's content; the document is anchored to the top with
 *    transparent padding below.
 *  - **Tight** (red shorter than blue): host slot is shorter than
 *    the document's content; with `paint-measure` the player still
 *    paints the doc into its declared rect (clipping at the slot);
 *    with the wrap-friendly profile the player measures against the
 *    parent's `maxHeight` and the doc shrinks to fit.
 *
 * Two profile variants:
 *
 *  - `androidXExperimental` — sets `FEATURE_PAINT_MEASURE = 1` (the
 *    alpha default). The player reports the document's declared size
 *    to its parent rather than the layout's intrinsic size, so a
 *    `wrapContentHeight()` host can't shrink to content.
 *  - `androidXExperimentalWrap` — sets `FEATURE_PAINT_MEASURE = 0`,
 *    so the player can run a real measure pass and report
 *    intrinsic content size up to the host. Required (along with
 *    `RemoteComposePlayerFlags.shouldPlayerWrapContentSize = true` on
 *    the player side) for adaptive wrap-content slot sizing.
 *
 * Renders both 411-dp-wide (mobile) and a wider canvas so dashboard
 * stacking is visible at typical content widths.
 */

private const val EXP_WIDTH = 411
private const val EXP_HEIGHT = 1100

@Preview(name = "sizing — fixed slot, paint-measure profile",
    widthDp = EXP_WIDTH, heightDp = EXP_HEIGHT, showBackground = true,
    backgroundColor = 0xFFFFFFFFL,
)
@Composable
fun Sizing_FixedSlot_PaintMeasure() {
    DebugStack(
        title = "fixed slot · paint-measure (FEATURE_PAINT_MEASURE = 1)",
        profile = androidXExperimental,
        slotHeightStrategy = SlotHeightStrategy.NaturalDp(),
    )
}

@Preview(name = "sizing — slack slot, paint-measure profile",
    widthDp = EXP_WIDTH, heightDp = EXP_HEIGHT, showBackground = true,
    backgroundColor = 0xFFFFFFFFL,
)
@Composable
fun Sizing_SlackSlot_PaintMeasure() {
    DebugStack(
        title = "+24 dp slack · paint-measure",
        profile = androidXExperimental,
        slotHeightStrategy = SlotHeightStrategy.NaturalDpPlus(24),
    )
}

@Preview(name = "sizing — tight slot, paint-measure profile",
    widthDp = EXP_WIDTH, heightDp = EXP_HEIGHT, showBackground = true,
    backgroundColor = 0xFFFFFFFFL,
)
@Composable
fun Sizing_TightSlot_PaintMeasure() {
    DebugStack(
        title = "−16 dp tight · paint-measure",
        profile = androidXExperimental,
        slotHeightStrategy = SlotHeightStrategy.NaturalDpPlus(-16),
    )
}

@Preview(name = "sizing — fixed slot, wrap profile",
    widthDp = EXP_WIDTH, heightDp = EXP_HEIGHT, showBackground = true,
    backgroundColor = 0xFFFFFFFFL,
)
@Composable
fun Sizing_FixedSlot_Wrap() {
    DebugStack(
        title = "fixed slot · wrap profile (FEATURE_PAINT_MEASURE = 0)",
        profile = androidXExperimentalWrap,
        slotHeightStrategy = SlotHeightStrategy.NaturalDp(),
    )
}

@Preview(name = "sizing — slack slot, wrap profile",
    widthDp = EXP_WIDTH, heightDp = EXP_HEIGHT, showBackground = true,
    backgroundColor = 0xFFFFFFFFL,
)
@Composable
fun Sizing_SlackSlot_Wrap() {
    DebugStack(
        title = "+24 dp slack · wrap profile",
        profile = androidXExperimentalWrap,
        slotHeightStrategy = SlotHeightStrategy.NaturalDpPlus(24),
    )
}

@Preview(name = "sizing — tight slot, wrap profile",
    widthDp = EXP_WIDTH, heightDp = EXP_HEIGHT, showBackground = true,
    backgroundColor = 0xFFFFFFFFL,
)
@Composable
fun Sizing_TightSlot_Wrap() {
    DebugStack(
        title = "−16 dp tight · wrap profile",
        profile = androidXExperimentalWrap,
        slotHeightStrategy = SlotHeightStrategy.NaturalDpPlus(-16),
    )
}

// Wrap-content host scenarios are intentionally absent: today
// `CachedCardPreview` wraps its slot in `Box(modifier.fillMaxSize())`,
// which forces the host to fill the parent's bounded height regardless
// of any `wrapContentHeight()` the caller chains. To test
// adaptive-wrap end-to-end the slot box would need to be
// `Box(modifier)` (without fillMaxSize) and the player would need
// `RemoteComposePlayerFlags.shouldPlayerWrapContentSize = true` so it
// reports intrinsic size up. See followup notes in this file's tail.

private sealed interface SlotHeightStrategy {
    /** Pin to the converter's [naturalHeightDp]. */
    data class NaturalDp(val unit: Unit = Unit) : SlotHeightStrategy
    /** Pin to natural + [delta] dp (negative for "tight"). */
    data class NaturalDpPlus(val delta: Int) : SlotHeightStrategy
}

@Composable
private fun DebugStack(
    title: String,
    profile: Profile,
    slotHeightStrategy: SlotHeightStrategy,
) {
    val registry = defaultRegistry()
    val cards = experimentCards()
    CompositionLocalProvider(LocalRcDebugBorders provides true) {
        ProvideCardRegistry(registry) {
            ProvideHaTheme(HaTheme.Light) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    cards.forEach { (label, card) ->
                        ExperimentRow(
                            label = label,
                            card = card,
                            profile = profile,
                            slotHeightStrategy = slotHeightStrategy,
                            registry = registry,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExperimentRow(
    label: String,
    card: CardConfig,
    profile: Profile,
    slotHeightStrategy: SlotHeightStrategy,
    registry: ee.schimke.ha.rc.CardRegistry,
) {
    val snapshot = Fixtures.mixed
    val natural = registry.cardHeightDp(card, snapshot)
    val (slotModifier, sizeLabel) = when (slotHeightStrategy) {
        is SlotHeightStrategy.NaturalDp ->
            Modifier.fillMaxWidth().height(natural.dp) to "host=${natural}dp (natural)"
        is SlotHeightStrategy.NaturalDpPlus ->
            Modifier
                .fillMaxWidth()
                .height((natural + slotHeightStrategy.delta).coerceAtLeast(8).dp) to
                "host=${natural + slotHeightStrategy.delta}dp"
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "$label  · natural=${natural}dp · $sizeLabel",
            style = MaterialTheme.typography.labelSmall,
        )
        // Outer green border = the parent layout's row band, so we can
        // see how `wrapContentHeight()` behaves vs a pinned height.
        Box(
            modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF2E7D32)).padding(1.dp),
        ) {
            CachedCardPreview(
                cacheKey = ExperimentCacheKey(card, profile, slotHeightStrategy),
                profile = profile,
                modifier = slotModifier
                    .border(1.dp, Color(0xFFD32F2F)),
            ) {
                ProvideCardRegistry(registry) {
                    ProvideHaTheme(HaTheme.Light) {
                        RenderChild(card, snapshot, RemoteModifier.rcFillMaxWidth())
                    }
                }
            }
        }
    }
}

/**
 * Composite cache key that includes the profile + slot strategy, so
 * the same `(card, theme)` pair re-encodes when the experiment varies
 * the profile / strategy.
 */
private data class ExperimentCacheKey(
    val card: CardConfig,
    val profile: Profile,
    val strategy: SlotHeightStrategy,
)

private fun experimentCards(): List<Pair<String, CardConfig>> = listOf(
    "tile" to card("""{"type":"tile","entity":"sensor.living_room"}"""),
    "button" to card("""{"type":"button","entity":"light.kitchen","name":"Kitchen"}"""),
    "entity" to card("""{"type":"entity","entity":"sensor.living_room"}"""),
    "entities" to card(
        """{"type":"entities","title":"Living Room","entities":[
            "sensor.living_room","light.kitchen","switch.coffee_maker"
        ]}"""
    ),
    "glance" to card(
        """{"type":"glance","title":"Overview","entities":[
            "sensor.living_room","light.kitchen","lock.front_door"
        ]}"""
    ),
)
