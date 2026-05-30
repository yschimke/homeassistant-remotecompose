package ee.schimke.terrazzo.previews

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.preview.AnimatedPreview
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.View
import ee.schimke.terrazzo.dashboard.CardChangeFlash
import ee.schimke.terrazzo.dashboard.DataGridOverlay
import kotlin.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Previews for the two debug data-visualisation features
 * (Settings → "Flash cards on data change" / "Data grid overlay").
 * These live in `src/debug` so the `@AnimatedPreview` annotation
 * dependency stays out of the release APK; the composables under test
 * are `internal` to the app module and visible from here.
 */

private fun entityCard(type: String, entity: String): CardConfig =
    CardConfig(
        type = type,
        raw =
            buildJsonObject {
                put("type", type)
                put("entity", entity)
            },
    )

private fun demoDashboard(): Dashboard =
    Dashboard(
        title = "Home",
        views =
            listOf(
                View(
                    cards =
                        listOf(
                            entityCard("tile", "light.living_room"),
                            entityCard("sensor", "sensor.living_room_temperature"),
                            entityCard("tile", "climate.thermostat"),
                            entityCard("tile", "lock.front_door"),
                            entityCard("entity", "binary_sensor.front_door_motion"),
                            entityCard("tile", "switch.coffee_machine"),
                        )
                )
            ),
    )

/**
 * Static render of [DataGridOverlay] floating over a faux dashboard.
 * `last_changed` values are stamped relative to render time so the
 * "ago" column shows a realistic spread instead of all "—".
 */
@Preview(name = "Data grid overlay", widthDp = 412, heightDp = 680, showBackground = true)
@Composable
private fun DataGridOverlayPreview() {
    val base = remember { System.currentTimeMillis() }
    fun ent(id: String, state: String, agoMs: Long): EntityState =
        EntityState(entityId = id, state = state, lastChanged = Instant.fromEpochMilliseconds(base - agoMs))

    val snapshot =
        HaSnapshot(
            states =
                listOf(
                        ent("light.living_room", "on", 4_000),
                        ent("sensor.living_room_temperature", "21.4 °C", 12_000),
                        ent("climate.thermostat", "heat", 3_000),
                        ent("lock.front_door", "locked", 95_000),
                        ent("binary_sensor.front_door_motion", "off", 330_000),
                        ent("switch.coffee_machine", "off", 61_000),
                    )
                    .associateBy { it.entityId }
        )

    Box(Modifier.fillMaxSize().background(Color(0xFFEFEFF3))) {
        FauxDashboardBackdrop()
        DataGridOverlay(dashboard = demoDashboard(), snapshot = snapshot, onClose = {})
    }
}

/**
 * Animated render of [CardChangeFlash]. An infinite transition sweeps a
 * counter whose integer buckets become the card's data signature, so
 * the slot flashes each time the bucket flips — the same path a live
 * entity-state change drives. `@AnimatedPreview` captures the fade as a
 * GIF.
 */
@Preview(name = "Card flash on data change", widthDp = 240, heightDp = 180, showBackground = true)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@Composable
private fun CardFlashPreview() {
    val transition = rememberInfiniteTransition(label = "flash-demo")
    val sweep by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "sweep",
        )
    val tick = sweep.toInt()
    val signature = "occupancy=$tick"

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box {
            FauxTile(stateLabel = if (tick % 2 == 0) "On" else "Off")
            CardChangeFlash(signature)
        }
    }
}

/** A tile-shaped placeholder so the flash highlight reads as "a card". */
@Composable
private fun FauxTile(stateLabel: String) {
    Surface(
        color = Color(0xFFE8DEF8),
        contentColor = Color(0xFF1D1B20),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.size(width = 180.dp, height = 110.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.Lightbulb, contentDescription = null, modifier = Modifier.size(28.dp))
            Text("Living room", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(stateLabel, fontSize = 12.sp, color = Color(0xFF49454F))
        }
    }
}

/** Light grey placeholder rectangles standing in for dashboard cards. */
@Composable
private fun FauxDashboardBackdrop() {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 380.dp, start = 12.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(3) {
            Box(
                Modifier.fillMaxWidth()
                    .height(72.dp)
                    .background(Color(0xFFD9D9E3), RoundedCornerShape(16.dp))
            )
        }
    }
}
