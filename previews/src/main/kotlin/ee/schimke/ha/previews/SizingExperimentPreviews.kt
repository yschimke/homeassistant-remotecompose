@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.enableRemoteComposeWrapContent

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
    enableRemoteComposeWrapContent()
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
    enableRemoteComposeWrapContent()
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
    enableRemoteComposeWrapContent()
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
    enableRemoteComposeWrapContent()
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
    enableRemoteComposeWrapContent()
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
    enableRemoteComposeWrapContent()
    DebugStack(
        title = "−16 dp tight · wrap profile",
        profile = androidXExperimentalWrap,
        slotHeightStrategy = SlotHeightStrategy.NaturalDpPlus(-16),
    )
}

// ── width × height constraint matrix ─────────────────────────────────
//
// Confirms how the player adapts to host-imposed size constraints.
// Width re-measures cleanly: the document's outer
// `RemoteBox(fillMaxWidth)` lays out its children at the host's
// width and Compose reports the resulting bitmap accordingly.
// Height is pinned in the matrix below for two reasons:
//   1. EXACTLY constraints from a pinned slot are a useful baseline.
//   2. The wrap-h-with-paint-context warmup happens inside
//      `WrapAdaptiveRemoteDocumentPlayer` (used by
//      `CachedCardPreview`), which is what unblocked end-to-end
//      adaptive sizing in the dashboard. See
//      `Sizing_WidthPinnedHeightAdaptive` for the wrap-h demo.

@Preview(name = "sizing — width × height matrix · wrap profile",
    widthDp = 800, heightDp = 1300, showBackground = true,
    backgroundColor = 0xFFFFFFFFL,
)
@Composable
fun Sizing_ConstraintMatrix_Wrap() {
    enableRemoteComposeWrapContent()
    ConstraintMatrix(
        title = "width × height matrix · wrap profile",
        profile = androidXExperimentalWrap,
    )
}

@Preview(name = "sizing — width × height matrix · paint-measure profile",
    widthDp = 800, heightDp = 1300, showBackground = true,
    backgroundColor = 0xFFFFFFFFL,
)
@Composable
fun Sizing_ConstraintMatrix_PaintMeasure() {
    enableRemoteComposeWrapContent()
    ConstraintMatrix(
        title = "width × height matrix · paint-measure profile",
        profile = androidXExperimental,
    )
}

@Preview(name = "sizing — width pinned, height adaptive",
    widthDp = EXP_WIDTH, heightDp = 800, showBackground = true,
    backgroundColor = 0xFFFFFFFFL,
)
@Composable
fun Sizing_WidthPinnedHeightAdaptive() {
    enableRemoteComposeWrapContent()
    WidthOnlyDemo()
}

private enum class CardKind(val baselineHeightDp: Int) {
    Tile(43),
    Button(91),
    Entity(48),
    Entities(184), // 36 (title) + 16 (pad) + 3 * 44 (rows)
    Glance(152), // 40 (title) + 112 (cell)
}

private sealed interface SlotHeightStrategy {
    /** Pin to the converter's [CardKind.baselineHeightDp]. */
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
                    cards.forEach { (label, card, kind) ->
                        ExperimentRow(
                            label = label,
                            card = card,
                            kind = kind,
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
    kind: CardKind,
    profile: Profile,
    slotHeightStrategy: SlotHeightStrategy,
    registry: ee.schimke.ha.rc.CardRegistry,
) {
    val snapshot = Fixtures.mixed
    val (slotModifier, sizeLabel) =
        when (slotHeightStrategy) {
            is SlotHeightStrategy.NaturalDp ->
                Modifier.fillMaxWidth().height(kind.baselineHeightDp.dp) to
                    "host=${kind.baselineHeightDp}dp (natural)"
            is SlotHeightStrategy.NaturalDpPlus ->
                Modifier.fillMaxWidth().height(
                    (kind.baselineHeightDp + slotHeightStrategy.delta).coerceAtLeast(8).dp
                ) to "host=${kind.baselineHeightDp + slotHeightStrategy.delta}dp"
        }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "$label  · natural=${kind.baselineHeightDp}dp · $sizeLabel",
            style = MaterialTheme.typography.labelSmall,
        )
        // Outer green border = the parent layout's row band, so we can
        // see how `wrapContentHeight()` behaves vs a pinned height.
        Box(
            modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF2E7D32)).padding(1.dp),
        ) {
            CachedCardPreview(
                cacheKey = ExperimentCacheKey(card, kind, profile, slotHeightStrategy),
                profile = profile,
                modifier = slotModifier.border(1.dp, Color(0xFFD32F2F)),
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
 * Composite cache key that includes the profile + slot strategy, so the same `(card, theme)` pair
 * re-encodes when the experiment varies the profile / strategy.
 */
private data class ExperimentCacheKey(
    val card: CardConfig,
    val kind: CardKind,
    val profile: Profile,
    val strategy: SlotHeightStrategy,
)

/**
 * Stacks one tile + one entities card with `Modifier.width(180.dp)` and **no** height modifier — the
 * host slot is asked to shrink to the document's intrinsic content height. Renders both
 * `androidXExperimental` (paint-measure) and `androidXExperimentalWrap` profiles side-by-side.
 *
 * **Expected** (if wrap-content worked end-to-end): the slot height matches the blue in-document
 * border, e.g. ~43 dp for tile.
 *
 * **Actual** (alpha010): the captured document does measure to intrinsic height — you can see the
 * blue border at the right place. But the player's `RemoteComposeView` reports parent's max height
 * back up to Compose, so the host slot stretches all the way down, leaving an empty band below the
 * blue border. That's why the dashboard still pins `Modifier.height(naturalHeightDp.dp)` rather than
 * relying on adaptive height.
 */
@Composable
private fun WidthOnlyDemo() {
    val registry = defaultRegistry()
    val cards =
        listOf(
            Triple("tile", card("""{"type":"tile","entity":"sensor.living_room"}"""), CardKind.Tile),
            Triple(
                "entities",
                card(
                    """{"type":"entities","title":"Living Room","entities":[
                "sensor.living_room","light.kitchen"
            ]}"""
                ),
                CardKind.Entities,
            ),
        )
    CompositionLocalProvider(LocalRcDebugBorders provides true) {
        ProvideCardRegistry(registry) {
            ProvideHaTheme(HaTheme.Light) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "width=180dp · height=wrap (now adapts via WrapAdaptive player)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    cards.forEach { (label, cardConfig, kind) ->
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF2E7D32))) {
                            CachedCardPreview(
                                cacheKey = WidthOnlyKey(cardConfig, kind, 180),
                                profile = androidXExperimentalWrap,
                                modifier = Modifier.width(180.dp).border(1.dp, Color(0xFFD32F2F)),
                            ) {
                                ProvideCardRegistry(registry) {
                                    ProvideHaTheme(HaTheme.Light) {
                                        RenderChild(
                                            cardConfig,
                                            Fixtures.mixed,
                                            RemoteModifier.rcFillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class WidthOnlyKey(val card: CardConfig, val kind: CardKind, val widthDp: Int)

/**
 * Sweeps width × height constraints. The "primary" question this answers: **can we constrain width
 * and let height adapt?** That's the widget / dashboard-column pattern (target column width fixed,
 * content drives height). Variants:
 * - **w=180 × natural**: width pinned, height pinned to the converter's `naturalHeightDp`. Document
 *   re-measures content at the requested width — tile elides label, glance icons reflow.
 * - **w=180 × wrap**: width pinned, **no** height modifier. This is the adaptive-height candidate.
 *   In alpha010 the `RemoteComposeView` does not shrink to intrinsic content when parent maxHeight
 *   is unbounded; the slot ends up taking the parent's available height instead. Demonstrates the
 *   limitation.
 * - **w=fillMax × natural / wrap**: same comparison but width fills the row.
 * - **w=380 × h=80**: both pinned, height < natural so content clips. Confirms the EXACTLY
 *   constraint short-circuits the wrap path — the slot is exactly 80 dp tall regardless of card
 *   type.
 *
 * The bordered green band around each row is the parent layout's row band; it lets you see the
 * difference between `Modifier.width(W)` (host shrinks horizontally) and `Modifier.fillMaxWidth()`
 * (host fills available width).
 */
@Composable
private fun ConstraintMatrix(title: String, profile: Profile) {
    val registry = defaultRegistry()
    val cards =
        listOf(
            Triple("tile", card("""{"type":"tile","entity":"sensor.living_room"}"""), CardKind.Tile),
            Triple(
                "entities",
                card(
                    """{"type":"entities","title":"Living Room","entities":[
                "sensor.living_room","light.kitchen","switch.coffee_maker"
            ]}"""
                ),
                CardKind.Entities,
            ),
            Triple(
                "glance",
                card(
                    """{"type":"glance","title":"Overview","entities":[
                "sensor.living_room","light.kitchen","lock.front_door"
            ]}"""
                ),
                CardKind.Glance,
            ),
        )
    val variants =
        listOf(
            Variant("w=180 × natural", widthDp = 180, heightDp = null, wrapHeight = false),
            Variant("w=300 × natural", widthDp = 300, heightDp = null, wrapHeight = false),
            Variant("w=fillMax × natural", widthDp = -1, heightDp = null, wrapHeight = false),
            Variant("w=380 × h=80 (clip)", widthDp = 380, heightDp = 80, wrapHeight = false),
        )
    CompositionLocalProvider(LocalRcDebugBorders provides true) {
        ProvideCardRegistry(registry) {
            ProvideHaTheme(HaTheme.Light) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    cards.forEach { (label, cardConfig, kind) ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        variants.forEach { variant ->
                            ConstraintRow(
                                variant = variant,
                                card = cardConfig,
                                kind = kind,
                                profile = profile,
                                registry = registry,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class Variant(
    val label: String,
    /** dp, or `-1` for `fillMaxWidth()`. */
    val widthDp: Int,
    /** dp; `null` + `!wrapHeight` falls back to the converter's `naturalHeightDp`. */
    val heightDp: Int?,
    /** When `true`, no height modifier — the slot tries to wrap to content. */
    val wrapHeight: Boolean,
)

@Composable
private fun ConstraintRow(
    variant: Variant,
    card: CardConfig,
    kind: CardKind,
    profile: Profile,
    registry: ee.schimke.ha.rc.CardRegistry,
) {
    val snapshot = Fixtures.mixed
    val widthMod =
        if (variant.widthDp == -1) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.width(variant.widthDp.dp)
        }
    val (slotMod, heightLabel) =
        when {
            variant.wrapHeight -> widthMod to "wrap"
            variant.heightDp != null ->
                widthMod.height(variant.heightDp.dp) to "${variant.heightDp}dp"
            else -> widthMod.height(kind.baselineHeightDp.dp) to "${kind.baselineHeightDp}dp"
        }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "  · ${variant.label}  (host=${variant.widthDp}×$heightLabel)",
            style = MaterialTheme.typography.labelSmall,
        )
        // Outer green border = the parent row band so we can see how
        // `Modifier.width(W)` (host shrinks horizontally) compares to
        // `Modifier.fillMaxWidth()` (host fills available width).
        Row(modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF2E7D32))) {
            CachedCardPreview(
                cacheKey = ConstraintCacheKey(card, kind, profile, variant),
                profile = profile,
                modifier = slotMod.border(1.dp, Color(0xFFD32F2F)),
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

private data class ConstraintCacheKey(
    val card: CardConfig,
    val kind: CardKind,
    val profile: Profile,
    val variant: Variant,
)

private fun experimentCards(): List<Triple<String, CardConfig, CardKind>> =
    listOf(
        Triple("tile", card("""{"type":"tile","entity":"sensor.living_room"}"""), CardKind.Tile),
        Triple(
            "button",
            card("""{"type":"button","entity":"light.kitchen","name":"Kitchen"}"""),
            CardKind.Button,
        ),
        Triple(
            "entity",
            card("""{"type":"entity","entity":"sensor.living_room"}"""),
            CardKind.Entity,
        ),
        Triple(
            "entities",
            card(
                """{"type":"entities","title":"Living Room","entities":[
            "sensor.living_room","light.kitchen","switch.coffee_maker"
        ]}"""
            ),
            CardKind.Entities,
        ),
        Triple(
            "glance",
            card(
                """{"type":"glance","title":"Overview","entities":[
            "sensor.living_room","light.kitchen","lock.front_door"
        ]}"""
            ),
            CardKind.Glance,
        ),
    )

