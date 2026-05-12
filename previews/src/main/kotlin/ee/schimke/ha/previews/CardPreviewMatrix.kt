@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth as rcFillMaxWidth
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
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.LocalPreviewClock
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.ProvideCardSizeMode
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.enableRemoteComposeWrapContent
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Single preview template that renders the same card across the three
 * surfaces it ships on:
 *
 *   a) **App preferred** (wrap mode) — the in-app dashboard tile. The
 *      template chooses what to show; height flows from the captured
 *      document's intrinsic content via [CachedCardPreview]'s wrap
 *      adaptive player. Width pins to [appWidthDp].
 *   b) **Three launcher widget sizes** (fixed mode) — the user's
 *      base [baseGridSize], plus a smaller (±1 cell) and larger
 *      (±1 cell) variant. Cells are sized via [LauncherCellWidthDp] /
 *      [LauncherCellHeightDp]; the card runs through the rc-converter
 *      with `CardSizeMode.Fixed`, so converters that opted into
 *      `RemoteSizeBreakpoint` adapt to each cell's runtime width.
 *   c) **Two wear widget sizes** (fixed mode) — the small and large
 *      Glance Wear container shapes the slot widgets advertise.
 *
 * Wear is dark-only and the launcher widgets render against the
 * launcher's dark surface; the app dashboard tile shares the same
 * Compose theme model. The matrix uses [HaTheme.Dark] for every cell —
 * one preview is the source of truth for what the card looks like at
 * each scale.
 */

const val LauncherCellWidthDp: Int = 72
const val LauncherCellHeightDp: Int = 84

/**
 * Launcher widget size in cells. Each cell is [LauncherCellWidthDp] ×
 * [LauncherCellHeightDp] dp. Use [`-`] / [`+`] to derive the
 * smaller / larger neighbouring sizes that share the same aspect; the
 * smaller variant clamps to a `1×1` floor so a `2×1` chip doesn't try
 * to shrink to a degenerate `1×0` cell.
 */
data class WidgetGridSize(val cellsW: Int, val cellsH: Int) {
    init {
        require(cellsW >= 1 && cellsH >= 1) { "cells must be >= 1: ${cellsW}x${cellsH}" }
    }

    val widthDp: Int get() = cellsW * LauncherCellWidthDp
    val heightDp: Int get() = cellsH * LauncherCellHeightDp

    operator fun plus(delta: Int): WidgetGridSize =
        WidgetGridSize(cellsW + delta, cellsH + delta)

    operator fun minus(delta: Int): WidgetGridSize =
        WidgetGridSize(
            cellsW = (cellsW - delta).coerceAtLeast(1),
            cellsH = (cellsH - delta).coerceAtLeast(1),
        )

    val label: String get() = "${cellsW}×${cellsH}"
}

private val WearSmall = WidgetSizeDp(widthDp = 200, heightDp = 60)
private val WearLarge = WidgetSizeDp(widthDp = 200, heightDp = 112)

/**
 * Wear watches typically run at ~2.0× density (xhdpi). The launcher /
 * mobile dashboard cells inherit the @Preview's density (≈2.625×, the
 * project standard for HA reference parity), so each surface in the
 * matrix renders at the density it would on the real device.
 */
private const val WearDensityScale: Float = 2f

private data class WidgetSizeDp(val widthDp: Int, val heightDp: Int)

/**
 * Frozen "now" so converters that read wall-clock time encode a
 * static label — same trick as [CardPreviews]; keeps the rendered
 * preview deterministic.
 */
private val PreviewNow: ZonedDateTime =
    ZonedDateTime.of(2026, 5, 5, 10, 8, 0, 0, ZoneOffset.UTC)

@Composable
fun CardPreviewMatrix(
    card: CardConfig,
    snapshot: HaSnapshot,
    baseGridSize: WidgetGridSize,
    appWidthDp: Int = 320,
    label: String? = null,
) {
    enableRemoteComposeWrapContent()
    val registry = defaultRegistry()
    CompositionLocalProvider(LocalPreviewClock provides PreviewNow) {
        ProvideCardRegistry(registry) {
            ProvideHaTheme(HaTheme.Dark) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (label != null) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = HaTheme.Dark.primaryText,
                        )
                    }
                    // Row 1 — App preferred + the two Wear container shapes.
                    // App and Wear render at watch / mobile densities (Wear
                    // cells override LocalDensity to 2×); grouping them on
                    // the same line keeps the "dashboard tile + wear tile"
                    // story together.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WrapModeCell(
                            card = card,
                            snapshot = snapshot,
                            cellWidthDp = appWidthDp,
                            label = "App ${appWidthDp}dp",
                        )
                        FixedModeCell(
                            card = card,
                            snapshot = snapshot,
                            sizeDp = WearSmall,
                            label = "Wear S",
                            densityScale = WearDensityScale,
                        )
                        FixedModeCell(
                            card = card,
                            snapshot = snapshot,
                            sizeDp = WearLarge,
                            label = "Wear L",
                            densityScale = WearDensityScale,
                        )
                    }
                    // Row 2 — three launcher widget sizes around the base
                    // (smaller, base, larger). All share the launcher
                    // density inherited from the @Preview.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FixedModeCell(
                            card = card,
                            snapshot = snapshot,
                            sizeDp = WidgetSizeDp(
                                (baseGridSize - 1).widthDp,
                                (baseGridSize - 1).heightDp,
                            ),
                            label = "Widget ${(baseGridSize - 1).label}",
                        )
                        FixedModeCell(
                            card = card,
                            snapshot = snapshot,
                            sizeDp = WidgetSizeDp(baseGridSize.widthDp, baseGridSize.heightDp),
                            label = "Widget ${baseGridSize.label}",
                        )
                        FixedModeCell(
                            card = card,
                            snapshot = snapshot,
                            sizeDp = WidgetSizeDp(
                                (baseGridSize + 1).widthDp,
                                (baseGridSize + 1).heightDp,
                            ),
                            label = "Widget ${(baseGridSize + 1).label}",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WrapModeCell(
    card: CardConfig,
    snapshot: HaSnapshot,
    cellWidthDp: Int,
    label: String,
) {
    // Wrap mode: outer column width pinned, height adaptive. Border
    // hugs the card's intrinsic content so the cell shape and the
    // card chrome line up.
    CellLabelled(label = label, widthDp = cellWidthDp) {
        Box(modifier = Modifier.width(cellWidthDp.dp).cellOutline()) {
            CachedCardPreview(
                cacheKey = MatrixCellKey(card, CardSizeMode.Wrap, cellWidthDp, null),
                profile = androidXExperimentalWrap,
                modifier = Modifier.width(cellWidthDp.dp),
                card = card,
                snapshot = snapshot,
            ) {
                ProvideCardRegistry(defaultRegistry()) {
                    ProvideHaTheme(HaTheme.Dark) {
                        ProvideCardSizeMode(CardSizeMode.Wrap) {
                            RenderChild(card, snapshot, RemoteModifier.rcFillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FixedModeCell(
    card: CardConfig,
    snapshot: HaSnapshot,
    sizeDp: WidgetSizeDp,
    label: String,
    densityScale: Float? = null,
) {
    // Fixed mode: cell is exactly sizeDp. Border draws the container
    // shape so the launcher / wear widget bounds are visible even
    // when the card itself doesn't fill the cell.
    //
    // [densityScale] overrides LocalDensity for the cell so wear cells
    // can render at watch-typical 2× density while launcher cells stay
    // at the @Preview's mobile density. The captured `.rc` document
    // bakes its dp coordinates against this density.
    val parentDensity = LocalDensity.current
    val cellDensity =
        densityScale?.let { Density(it, parentDensity.fontScale) } ?: parentDensity
    CompositionLocalProvider(LocalDensity provides cellDensity) {
        CellLabelled(label = label, widthDp = sizeDp.widthDp) {
            Box(
                modifier =
                    Modifier.size(sizeDp.widthDp.dp, sizeDp.heightDp.dp).cellOutline(),
            ) {
                CachedCardPreview(
                    cacheKey =
                        MatrixCellKey(
                            card,
                            CardSizeMode.Fixed,
                            sizeDp.widthDp,
                            sizeDp.heightDp,
                            densityScale,
                        ),
                    profile = androidXExperimentalWrap,
                    modifier = Modifier.fillMaxSize(),
                    card = card,
                    snapshot = snapshot,
                ) {
                    ProvideCardRegistry(defaultRegistry()) {
                        ProvideHaTheme(HaTheme.Dark) {
                            ProvideCardSizeMode(CardSizeMode.Fixed) {
                                RenderChild(card, snapshot, RemoteModifier.rcFillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CellLabelled(
    label: String,
    widthDp: Int,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.width(widthDp.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = HaTheme.Dark.secondaryText,
        )
        content()
    }
}

/**
 * Hairline outline used to mark each matrix cell. Background stays
 * transparent so card-drawn surfaces still show through; the border is
 * subtle enough to read as "this is the cell shape" without competing
 * with the card's own chrome.
 */
private fun Modifier.cellOutline(): Modifier =
    this.border(
        width = 1.dp,
        color = HaTheme.Dark.divider,
    )

private data class MatrixCellKey(
    val card: CardConfig,
    val mode: CardSizeMode,
    val widthDp: Int,
    val heightDp: Int?,
    val densityScale: Float? = null,
)

// ── @Preview entries ─────────────────────────────────────────────────
//
// One preview per representative card. Each preview drives the matrix
// for the slot-widget reps adapted in this iteration: tile, entity,
// gauge, button, entities, plus the size-adaptive thermostat /
// humidifier / weather cards.
//
// Layout is two rows: row 1 is App + Wear S + Wear L; row 2 is the
// three launcher widget sizes (base ±1). Width pinned to 1100 dp so
// the widest row (a 6×3 weather widget at base+1 ≈ 432 dp) fits at
// typical preview density. Heights are tuned per card to fit both
// rows without padding the canvas — measured cell-by-cell.

private const val MATRIX_CANVAS_WIDTH_DP = 1100

@Preview(
    name = "matrix — tile",
    showBackground = false,
    widthDp = MATRIX_CANVAS_WIDTH_DP,
    heightDp = 260,
)
@Composable
fun CardPreviewMatrix_Tile() {
    val card = card("""{"type":"tile","entity":"sensor.living_room","name":"Living Room"}""")
    CardPreviewMatrix(
        card = card,
        snapshot = Fixtures.mixed,
        baseGridSize = WidgetGridSize(cellsW = 2, cellsH = 1),
        label = "tile · sensor.living_room",
    )
}

@Preview(
    name = "matrix — entity",
    showBackground = false,
    widthDp = MATRIX_CANVAS_WIDTH_DP,
    heightDp = 260,
)
@Composable
fun CardPreviewMatrix_Entity() {
    val card = card("""{"type":"entity","entity":"lock.front_door","name":"Front door"}""")
    CardPreviewMatrix(
        card = card,
        snapshot = Fixtures.mixed,
        baseGridSize = WidgetGridSize(cellsW = 3, cellsH = 1),
        label = "entity · lock.front_door",
    )
}

@Preview(
    name = "matrix — gauge",
    showBackground = false,
    widthDp = MATRIX_CANVAS_WIDTH_DP,
    heightDp = 380,
)
@Composable
fun CardPreviewMatrix_Gauge() {
    val card = card(
        """{"type":"gauge","entity":"sensor.living_room","name":"Battery","min":0,"max":100}"""
    )
    // Gauge's natural launcher shape is a horizontal pill (arc on the
    // left, value · name on the right) — the Reflowed tier — not the
    // tall arc-on-top stack that wrap mode shows. See
    // docs/architecture/adaptive-card-layouts.md §6.
    CardPreviewMatrix(
        card = card,
        snapshot = Fixtures.mixed,
        baseGridSize = WidgetGridSize(cellsW = 2, cellsH = 1),
        label = "gauge · sensor.living_room",
    )
}

@Preview(
    name = "matrix — button",
    showBackground = false,
    widthDp = MATRIX_CANVAS_WIDTH_DP,
    heightDp = 340,
)
@Composable
fun CardPreviewMatrix_Button() {
    val card = card(
        """{"type":"button","entity":"light.kitchen","name":"Kitchen","show_state":true}"""
    )
    CardPreviewMatrix(
        card = card,
        snapshot = Fixtures.mixed,
        baseGridSize = WidgetGridSize(cellsW = 2, cellsH = 1),
        label = "button · light.kitchen",
    )
}

@Preview(
    name = "matrix — thermostat",
    showBackground = false,
    widthDp = MATRIX_CANVAS_WIDTH_DP,
    heightDp = 660,
)
@Composable
fun CardPreviewMatrix_Thermostat() {
    val card = card("""{"type":"thermostat","entity":"climate.living_room"}""")
    CardPreviewMatrix(
        card = card,
        snapshot = Fixtures.thermostat,
        baseGridSize = WidgetGridSize(cellsW = 3, cellsH = 3),
        appWidthDp = 240,
        label = "thermostat · climate.living_room",
    )
}

@Preview(
    name = "matrix — humidifier",
    showBackground = false,
    widthDp = MATRIX_CANVAS_WIDTH_DP,
    heightDp = 660,
)
@Composable
fun CardPreviewMatrix_Humidifier() {
    val card = card("""{"type":"humidifier","entity":"humidifier.bedroom"}""")
    CardPreviewMatrix(
        card = card,
        snapshot = Fixtures.humidifier,
        baseGridSize = WidgetGridSize(cellsW = 3, cellsH = 3),
        appWidthDp = 240,
        label = "humidifier · humidifier.bedroom",
    )
}

@Preview(
    name = "matrix — weather-forecast",
    showBackground = false,
    widthDp = MATRIX_CANVAS_WIDTH_DP,
    heightDp = 460,
)
@Composable
fun CardPreviewMatrix_WeatherForecast() {
    val card = card(
        """{"type":"weather-forecast","entity":"weather.forecast_home",
            "show_current":true,"show_forecast":true}"""
    )
    CardPreviewMatrix(
        card = card,
        snapshot = Fixtures.weather,
        baseGridSize = WidgetGridSize(cellsW = 5, cellsH = 2),
        appWidthDp = 320,
        label = "weather-forecast · weather.forecast_home",
    )
}

@Preview(
    name = "matrix — entities",
    showBackground = false,
    widthDp = MATRIX_CANVAS_WIDTH_DP,
    heightDp = 620,
)
@Composable
fun CardPreviewMatrix_Entities() {
    val card = card(
        """{"type":"entities","title":"Living Room","entities":[
            "sensor.living_room","light.kitchen","switch.coffee_maker","lock.front_door"
        ]}"""
    )
    CardPreviewMatrix(
        card = card,
        snapshot = Fixtures.mixed,
        baseGridSize = WidgetGridSize(cellsW = 4, cellsH = 3),
        label = "entities · living-room cards",
    )
}
