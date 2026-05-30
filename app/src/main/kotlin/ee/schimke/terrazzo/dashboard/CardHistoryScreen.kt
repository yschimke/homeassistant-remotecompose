@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.dashboard

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth as rcFillMaxWidth
import coil3.SingletonImageLoader
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.HistoryPoint
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import ee.schimke.ha.rc.image.CoilBitmapLoader
import ee.schimke.terrazzo.LocalHaImageStack
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.ui.LocalIsDarkTheme
import ee.schimke.terrazzo.ui.LocalThemeStyle
import kotlinx.serialization.json.jsonPrimitive

/**
 * Generic "more info" / history screen, opened by long-pressing any
 * dashboard card. Three stacked regions:
 *
 *   1. A live render of the card itself at the top — the same
 *      `CachedCardPreview` path the dashboard and install sheet use, so
 *      it looks identical to what the user just pressed.
 *   2. A historical chart per entity the card references. Numeric
 *      entities (sensors) draw a line chart; everything else (lights,
 *      switches, binary sensors) draws an on/off-style state timeline.
 *      A range selector re-fetches over 24 h / 3 d / 7 d.
 *   3. "Other relevant links" — add the card to the home screen (the
 *      existing widget-install flow) and, for live instances, open the
 *      entity in Home Assistant's own history view.
 *
 * History is fetched on demand via [HaSession.fetchHistory]; the card
 * preview renders against the [snapshot] captured at long-press time, so
 * it matches the dashboard frame even on a session that doesn't poll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardHistoryScreen(
    session: HaSession,
    card: CardConfig,
    snapshot: HaSnapshot,
    onBack: () -> Unit,
    onAddToHomeScreen: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val entityIds = remember(card) { card.historyEntityIds() }
    var hours by remember { mutableIntStateOf(24) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = card.historyTitle(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            CardPreviewCard(session = session, card = card, snapshot = snapshot)

            if (entityIds.isEmpty()) {
                Text(
                    "This card isn't linked to a Home Assistant entity, so there's " +
                        "no history to show.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                HistorySection(
                    session = session,
                    entityIds = entityIds,
                    snapshot = snapshot,
                    hours = hours,
                    onHoursChange = { hours = it },
                )
            }

            LinksSection(
                session = session,
                entityIds = entityIds,
                onAddToHomeScreen = onAddToHomeScreen,
            )
        }
    }
}

/** Top region: the card rendered exactly as it appears on the dashboard. */
@Composable
private fun CardPreviewCard(session: HaSession, card: CardConfig, snapshot: HaSnapshot) {
    val context = LocalContext.current
    val registry = remember { defaultRegistry().withEnhancedShutter() }
    val style = LocalThemeStyle.current
    val dark = LocalIsDarkTheme.current
    val haTheme = remember(style, dark) { haThemeFor(style, dark) }
    val haImageStack = LocalHaImageStack.current
    val imageLoader =
        haImageStack?.imageLoader
            ?: remember(context) { SingletonImageLoader.get(context.applicationContext) }
    val bitmapLoader = remember(context, session.baseUrl, imageLoader) {
        CoilBitmapLoader(context.applicationContext, imageLoader = imageLoader, baseUrl = session.baseUrl)
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            CachedCardPreview(
                cacheKey = HistoryPreviewCacheKey(card, style, dark),
                profile = androidXExperimentalWrap,
                card = card,
                snapshot = snapshot,
                bitmapLoader = bitmapLoader,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ProvideCardRegistry(registry) {
                    ProvideHaTheme(haTheme) {
                        RenderChild(card, snapshot, RemoteModifier.rcFillMaxWidth())
                    }
                }
            }
        }
    }
}

private data class HistoryPreviewCacheKey(
    val card: CardConfig,
    val style: ThemeStyle,
    val dark: Boolean,
)

/** Middle region: range chips + one chart block per entity. */
@Composable
private fun HistorySection(
    session: HaSession,
    entityIds: List<String>,
    snapshot: HaSnapshot,
    hours: Int,
    onHoursChange: (Int) -> Unit,
) {
    Text("History", style = MaterialTheme.typography.titleMedium)

    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RANGE_OPTIONS.forEach { (label, optionHours) ->
            FilterChip(
                selected = hours == optionHours,
                onClick = { onHoursChange(optionHours) },
                label = { Text(label) },
            )
        }
    }

    // produceState keyed by (entityIds, hours) re-runs the fetch whenever
    // the user picks a new range; null means "still loading".
    val history by produceState<Map<String, List<HistoryPoint>>?>(
        initialValue = null,
        session,
        entityIds,
        hours,
    ) {
        value = null
        value = runCatching { session.fetchHistory(entityIds, hours) }.getOrDefault(emptyMap())
    }

    val current = history
    if (current == null) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    entityIds.forEachIndexed { index, id ->
        if (index > 0) HorizontalDivider()
        EntityHistoryBlock(
            entityId = id,
            name = friendlyName(snapshot, id),
            unit = unitOf(snapshot, id),
            points = current[id].orEmpty(),
        )
    }
}

@Composable
private fun EntityHistoryBlock(
    entityId: String,
    name: String,
    unit: String?,
    points: List<HistoryPoint>,
) {
    val numeric = remember(points) { points.mapNotNull { it.state.toFloatOrNull() } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, style = MaterialTheme.typography.titleSmall)
        when {
            points.isEmpty() ->
                Text(
                    "No history recorded for this entity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            numeric.size >= 2 -> {
                Text(summariseNumeric(numeric, unit), style = MaterialTheme.typography.bodySmall)
                NumericHistoryChart(values = numeric)
            }
            else -> {
                Text("${points.size} state changes", style = MaterialTheme.typography.bodySmall)
                StateTimeline(points = points)
            }
        }
    }
}

/** A simple normalised line chart, no axes — a detail-view sparkline. */
@Composable
private fun NumericHistoryChart(values: List<Float>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .padding(vertical = 4.dp),
    ) {
        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = if (values.size > 1) size.width / (values.size - 1) else size.width

        // Baseline + top gridline so a flat-ish line still reads as a chart.
        drawLine(
            color = gridColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = gridColor,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
        )

        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - ((v - min) / range) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = lineColor, style = Stroke(width = 2.dp.toPx()))
    }
}

/**
 * On/off-style timeline for non-numeric entities: each state run is a
 * coloured segment sized by its share of the window. "Active" states
 * (`on`, `open`, `home`, `playing`, …) read in the primary colour;
 * everything else in a muted surface tone.
 */
@Composable
private fun StateTimeline(points: List<HistoryPoint>) {
    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.surfaceVariant
    val tsStart = points.first().ts.toEpochMilliseconds()
    val tsEnd = points.last().ts.toEpochMilliseconds()
    val span = (tsEnd - tsStart).takeIf { it > 0 } ?: 1L
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(vertical = 4.dp),
    ) {
        points.forEachIndexed { i, point ->
            val segStart = point.ts.toEpochMilliseconds()
            val segEnd = points.getOrNull(i + 1)?.ts?.toEpochMilliseconds() ?: tsEnd
            val x0 = ((segStart - tsStart).toFloat() / span) * size.width
            val x1 = ((segEnd - tsStart).toFloat() / span) * size.width
            drawRect(
                color = if (point.state.isActiveState()) activeColor else idleColor,
                topLeft = Offset(x0, 0f),
                size = androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(1f), size.height),
            )
        }
    }
}

/** Bottom region: install + open-in-HA links. */
@Composable
private fun LinksSection(
    session: HaSession,
    entityIds: List<String>,
    onAddToHomeScreen: () -> Unit,
) {
    val context = LocalContext.current
    val isDemo = remember(session) { DemoData.isDemo(session.baseUrl) }

    Text("Other actions", style = MaterialTheme.typography.titleMedium)

    FilledTonalButton(onClick = onAddToHomeScreen, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.AddToHomeScreen, contentDescription = null)
        Text("  Add to Home Screen")
    }

    // The deep link only makes sense against a real instance; demo's
    // `demo://` base url has no web history view to open.
    if (!isDemo && entityIds.isNotEmpty()) {
        OutlinedButton(
            onClick = {
                val url = "${session.baseUrl.trimEnd('/')}/history?entity_id=${entityIds.first()}"
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Link, contentDescription = null)
            Text("  Open in Home Assistant")
        }
    }
}

private val RANGE_OPTIONS: List<Pair<String, Int>> =
    listOf("24 h" to 24, "3 d" to 72, "7 d" to 168)

private fun friendlyName(snapshot: HaSnapshot, entityId: String): String =
    snapshot.states[entityId]?.attributes?.get("friendly_name")?.jsonPrimitive?.content ?: entityId

private fun unitOf(snapshot: HaSnapshot, entityId: String): String? =
    snapshot.states[entityId]?.attributes?.get("unit_of_measurement")?.jsonPrimitive?.content

private fun summariseNumeric(values: List<Float>, unit: String?): String {
    val min = values.min()
    val max = values.max()
    val current = values.last()
    val suffix = unit?.let { " $it" } ?: ""
    val fmt: (Float) -> String = { v ->
        if (v == v.toLong().toFloat()) v.toLong().toString() else "%.1f".format(v)
    }
    return "now ${fmt(current)}$suffix · range ${fmt(min)}–${fmt(max)}$suffix · ${values.size} samples"
}

private val ACTIVE_STATES = setOf("on", "open", "home", "playing", "active", "unlocked", "detected")

private fun String.isActiveState(): Boolean = lowercase() in ACTIVE_STATES
