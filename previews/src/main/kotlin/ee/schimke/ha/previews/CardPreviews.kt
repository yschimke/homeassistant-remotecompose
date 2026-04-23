@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth as uiFillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.tooling.preview.RemotePreview
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme

/**
 * Previews for every card type.
 *
 * ───────────────────────────────────────────────────────────────────
 *  CANVAS SIZING RULE — DO NOT CHANGE WITHOUT READING THIS
 * ───────────────────────────────────────────────────────────────────
 *
 * Preview `widthDp` / `heightDp` are pinned to the HA reference
 * capture dimensions (the PNGs under `references/[card]/`), converted
 * from capture px to dp at density 2.625:
 *
 *   widthDp  = round(refCapturePx.width  / 2.625)
 *   heightDp = round(refCapturePx.height / 2.625)
 *
 * That makes the RC render and the HA screenshot the same pixel size
 * when viewed side-by-side in the gist — the comparison is honest.
 *
 * Rules:
 *
 *   1. Do **not** shrink the preview canvas below the reference, even
 *      if our converter emits content smaller than HA's card. The
 *      resulting transparent padding is a visible signal that the
 *      widget still needs to grow to match HA. Don't hide that gap by
 *      tightening the canvas.
 *
 *   2. Do **not** grow the canvas above the reference to add "safety
 *      margin" — Robolectric renders at a known density and clipping
 *      is already flagged by `scripts/check-preview-clipping.py`.
 *
 *   3. For card types with **no** HA reference (heading, grid,
 *      vertical-stack, horizontal-stack, unsupported) the canvas is
 *      tight to content bounds as measured by
 *      `scripts/check-preview-waste.py`. Don't round up.
 *
 *   4. The reference dimensions at 2.625 density are:
 *
 *        tile       187 ×  43 dp     (refs in `references/tile/`)
 *        button     187 ×  91 dp     (refs in `references/button/`)
 *        entity     187 ×  91 dp     (refs in `references/entity/`)
 *        entities   381 × 169 dp     (refs in `references/entities/`)
 *        glance     381 × 149 dp     (refs in `references/glance/`)
 *        markdown   381 × 106 dp     (refs in `references/markdown/`)
 *        dashboard  381 × 411 dp     (refs in `references/dashboard/`)
 *        home       561 × 251 dp     (refs in `references/home/`)
 *
 *   5. If the HA references are ever re-captured at a different
 *      density, re-run `scripts/ref-sizes.py` and update these
 *      numbers together — don't drift.
 *
 * ───────────────────────────────────────────────────────────────────
 *
 * State-variant cards (light on/off/unavailable, cover closed/open/
 * opening, lock locked/unlocked/locking) fan out via
 * [PreviewParameterProvider] so one function produces one PNG per
 * state.
 */

@Composable
private fun CardHost(theme: HaTheme, content: @Composable () -> Unit) {
    RemotePreview(profile = androidXExperimental) {
        ProvideCardRegistry(defaultRegistry()) {
            ProvideHaTheme(theme) { content() }
        }
    }
}

// ——— button ———

// Standalone button card — `fillMaxWidth()` so the card fills the
// preview canvas (matching HA's top-level button card). Buttons nested
// inside a grid / horizontal-stack use the default wrap-content.

@Preview(name = "button (light)", showBackground = false, widthDp = 187, heightDp = 91)
@Composable
fun Button_Light(
    @PreviewParameter(KitchenLightStatesProvider::class) param: Pair<String, HaSnapshot>,
) = CardHost(HaTheme.Light) {
    RenderChild(buttonCard(), param.second, RemoteModifier.fillMaxWidth())
}

@Preview(name = "button (dark)", showBackground = false, widthDp = 187, heightDp = 91)
@Composable
fun Button_Dark(
    @PreviewParameter(KitchenLightStatesProvider::class) param: Pair<String, HaSnapshot>,
) = CardHost(HaTheme.Dark) {
    RenderChild(buttonCard(), param.second, RemoteModifier.fillMaxWidth())
}

private fun buttonCard() = card(
    """{"type":"button","entity":"light.kitchen","name":"Kitchen","show_name":true}""",
)

// ——— entity ———

// Our converter emits a simple row that's visibly smaller than HA's
// tile-styled entity card; see `docs/followups/entity-card-redesign.md`.
// The empty space in the preview canvas is that gap — don't hide it.

@Preview(name = "entity (light)", showBackground = false, widthDp = 187, heightDp = 91)
@Composable
fun Entity_Light() = CardHost(HaTheme.Light) {
    RenderChild(entityCard(), Fixtures.livingRoomTemp)
}

@Preview(name = "entity (dark)", showBackground = false, widthDp = 187, heightDp = 91)
@Composable
fun Entity_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(entityCard(), Fixtures.livingRoomTemp)
}

private fun entityCard() = card("""{"type":"entity","entity":"sensor.living_room"}""")

// ——— entities ———

@Preview(name = "entities (light)", showBackground = false, widthDp = 381, heightDp = 169)
@Composable
fun Entities_Light() = CardHost(HaTheme.Light) {
    RenderChild(entitiesCard(), Fixtures.mixed)
}

@Preview(name = "entities (dark)", showBackground = false, widthDp = 381, heightDp = 169)
@Composable
fun Entities_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(entitiesCard(), Fixtures.mixed)
}

// Matches `integration/config/ui-lovelace.yaml` view=entities.
private fun entitiesCard() = card(
    """{"type":"entities","title":"Living Room","entities":[
        "sensor.living_room",
        "light.kitchen",
        "switch.coffee_maker"
    ]}""",
)

// ——— glance ———

@Preview(name = "glance (light)", showBackground = false, widthDp = 381, heightDp = 149)
@Composable
fun Glance_Light() = CardHost(HaTheme.Light) {
    RenderChild(glanceCard(), Fixtures.mixed)
}

@Preview(name = "glance (dark)", showBackground = false, widthDp = 381, heightDp = 149)
@Composable
fun Glance_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(glanceCard(), Fixtures.mixed)
}

// Matches `integration/config/ui-lovelace.yaml` view=glance.
private fun glanceCard() = card(
    """{"type":"glance","title":"Overview","entities":[
        "sensor.living_room",
        "light.kitchen",
        "lock.front_door"
    ]}""",
)

// ——— heading ———
// No HA reference. Tight-to-content, per rule 3 at top.

@Preview(name = "heading (light)", showBackground = false, widthDp = 108, heightDp = 18)
@Composable
fun Heading_Light() = CardHost(HaTheme.Light) {
    RenderChild(headingCard(), Fixtures.mixed)
}

@Preview(name = "heading (dark)", showBackground = false, widthDp = 108, heightDp = 18)
@Composable
fun Heading_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(headingCard(), Fixtures.mixed)
}

private fun headingCard() = card("""{"type":"heading","heading":"Downstairs"}""")

// ——— markdown ———

@Preview(name = "markdown (light)", showBackground = false, widthDp = 381, heightDp = 106)
@Composable
fun Markdown_Light() = CardHost(HaTheme.Light) {
    RenderChild(markdownCard(), Fixtures.mixed)
}

@Preview(name = "markdown (dark)", showBackground = false, widthDp = 381, heightDp = 106)
@Composable
fun Markdown_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(markdownCard(), Fixtures.mixed)
}

private fun markdownCard() = card(
    """{"type":"markdown","title":"Notes","content":"Welcome home.\nTemperature is normal."}""",
)

// ——— vertical-stack ———

@Preview(name = "vertical-stack (light)", showBackground = false, widthDp = 232, heightDp = 100)
@Composable
fun VerticalStack_Light() = CardHost(HaTheme.Light) {
    RenderChild(verticalStackCard(), Fixtures.mixed)
}

@Preview(name = "vertical-stack (dark)", showBackground = false, widthDp = 232, heightDp = 100)
@Composable
fun VerticalStack_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(verticalStackCard(), Fixtures.mixed)
}

private fun verticalStackCard() = card(
    """{"type":"vertical-stack","cards":[
        {"type":"tile","entity":"sensor.living_room"},
        {"type":"tile","entity":"light.kitchen"}
    ]}""",
)

// ——— horizontal-stack ———
// No HA reference. Tight-to-content, per rule 3 at top.

@Preview(name = "horizontal-stack (light)", showBackground = false, widthDp = 152, heightDp = 93)
@Composable
fun HorizontalStack_Light() = CardHost(HaTheme.Light) {
    RenderChild(horizontalStackCard(), Fixtures.mixed)
}

@Preview(name = "horizontal-stack (dark)", showBackground = false, widthDp = 152, heightDp = 93)
@Composable
fun HorizontalStack_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(horizontalStackCard(), Fixtures.mixed)
}

private fun horizontalStackCard() = card(
    """{"type":"horizontal-stack","cards":[
        {"type":"button","entity":"light.kitchen","name":"Kitchen"},
        {"type":"button","entity":"sensor.living_room","name":"Temp","icon":"mdi:thermometer"}
    ]}""",
)

// ——— grid ———
// No HA reference. Tight-to-content, per rule 3 at top.
// Our 4 buttons fit in one row at 312 dp.

@Preview(name = "grid (light)", showBackground = false, widthDp = 312, heightDp = 94)
@Composable
fun Grid_Light() = CardHost(HaTheme.Light) {
    RenderChild(gridCard(), Fixtures.mixed)
}

@Preview(name = "grid (dark)", showBackground = false, widthDp = 312, heightDp = 94)
@Composable
fun Grid_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(gridCard(), Fixtures.mixed)
}

private fun gridCard() = card(
    """{"type":"grid","cards":[
        {"type":"button","entity":"light.kitchen","name":"Kitchen"},
        {"type":"button","entity":"light.kitchen","name":"Office"},
        {"type":"button","entity":"sensor.living_room","name":"Temp","icon":"mdi:thermometer"},
        {"type":"button","entity":"sensor.living_room","name":"RH","icon":"mdi:water-percent"}
    ]}""",
)

// ——— dashboard (mixed-card demo) ———
//
// End-to-end example of the target architecture: each Lovelace card
// emits its **own** `.rc` document played in its own `RemotePreview`
// host, and the dashboard is a regular Compose `Column` that stacks
// those players. In the end-goal app each of these players becomes a
// separate Glance widget; this preview models that decomposition.
//
// Not a single big `.rc` for the whole vertical-stack — doing that
// would couple per-card state updates and defeat the widget-per-card
// model.

@Preview(name = "dashboard (light)", showBackground = false, widthDp = 381, heightDp = 411)
@Composable
fun Dashboard_Light() = DashboardHost(HaTheme.Light)

@Preview(name = "dashboard (dark)", showBackground = false, widthDp = 381, heightDp = 411)
@Composable
fun Dashboard_Dark() = DashboardHost(HaTheme.Dark)

/**
 * Row heights are pinned so the stack totals exactly the reference
 * height (381×411 dp). Each [PlayerSlot] hosts one independent
 * RemoteCompose document.
 */
@Composable
private fun DashboardHost(theme: HaTheme) {
    Column(
        modifier = Modifier.uiFillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        PlayerSlot(theme, heightDp = 36) {
            RenderChild(
                card("""{"type":"heading","heading":"Home"}"""),
                Fixtures.mixed,
                RemoteModifier.fillMaxWidth(),
            )
        }
        PlayerSlot(theme, heightDp = 48) {
            RenderChild(
                card("""{"type":"tile","entity":"sensor.living_room"}"""),
                Fixtures.mixed,
                RemoteModifier.fillMaxWidth(),
            )
        }
        PlayerSlot(theme, heightDp = 48) {
            RenderChild(
                card("""{"type":"tile","entity":"light.kitchen","color":"amber"}"""),
                Fixtures.mixed,
                RemoteModifier.fillMaxWidth(),
            )
        }
        PlayerSlot(theme, heightDp = 128) {
            RenderChild(
                card(
                    """{"type":"entities","title":"Living Room","entities":[
                        "light.kitchen","switch.coffee_maker"
                    ]}""",
                ),
                Fixtures.mixed,
                RemoteModifier.fillMaxWidth(),
            )
        }
        PlayerSlot(theme, heightDp = 151) {
            RenderChild(
                card(
                    """{"type":"glance","title":"Overview","entities":[
                        "sensor.living_room","light.kitchen","lock.front_door"
                    ]}""",
                ),
                Fixtures.mixed,
                RemoteModifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Bounded box hosting a single RemoteCompose document. Each slot has
 * a fixed height — `RemoteDocPreview` sizes the doc to its container,
 * so callers must pin the container size.
 */
@Composable
private fun PlayerSlot(
    theme: HaTheme,
    heightDp: Int,
    content: @Composable @RemoteComposable () -> Unit,
) {
    Box(modifier = Modifier.uiFillMaxWidth().height(heightDp.dp)) {
        RemotePreview(profile = androidXExperimental) {
            ProvideCardRegistry(defaultRegistry()) {
                ProvideHaTheme(theme) { content() }
            }
        }
    }
}

// ——— unsupported placeholder ———

@Preview(name = "unsupported (light)", showBackground = false, widthDp = 140, heightDp = 86)
@Composable
fun Unsupported_Light() = CardHost(HaTheme.Light) {
    RenderChild(unsupportedCard(), Fixtures.mixed)
}

@Preview(name = "unsupported (dark)", showBackground = false, widthDp = 140, heightDp = 86)
@Composable
fun Unsupported_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(unsupportedCard(), Fixtures.mixed)
}

private fun unsupportedCard() = card("""{"type":"gauge","entity":"sensor.living_room"}""")

// ——— tile, state variants via PreviewParameter ———

@Preview(name = "tile light (light)", showBackground = false, widthDp = 187, heightDp = 43)
@Composable
fun Tile_Light_States(
    @PreviewParameter(KitchenLightStatesProvider::class) param: Pair<String, HaSnapshot>,
) = CardHost(HaTheme.Light) {
    RenderChild(card("""{"type":"tile","entity":"light.kitchen"}"""), param.second)
}

@Preview(name = "tile cover (light)", showBackground = false, widthDp = 187, heightDp = 43)
@Composable
fun Tile_Cover_States(
    @PreviewParameter(GarageCoverStatesProvider::class) param: Pair<String, HaSnapshot>,
) = CardHost(HaTheme.Light) {
    RenderChild(card("""{"type":"tile","entity":"cover.garage"}"""), param.second)
}

@Preview(name = "tile lock (light)", showBackground = false, widthDp = 187, heightDp = 43)
@Composable
fun Tile_Lock_States(
    @PreviewParameter(FrontDoorLockStatesProvider::class) param: Pair<String, HaSnapshot>,
) = CardHost(HaTheme.Light) {
    RenderChild(card("""{"type":"tile","entity":"lock.front_door"}"""), param.second)
}
