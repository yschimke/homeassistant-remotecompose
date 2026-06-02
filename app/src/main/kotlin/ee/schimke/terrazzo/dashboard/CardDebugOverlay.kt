package ee.schimke.terrazzo.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.logs.referencedEntities
import kotlinx.coroutines.delay

/**
 * Debug-only host signals for the card data-visualisation features (Settings → "Flash cards on data
 * change" / "Data grid overlay"). Both read the polled [HaSnapshot] the dashboard already drives
 * its cards from — they add no new data source and change nothing about the captured RemoteCompose
 * documents.
 */

/**
 * When `true`, every [CardSlot] paints a brief fading highlight each time the entity state it
 * renders changes. Provided by [DashboardList] from
 * [ee.schimke.terrazzo.core.prefs.PreferencesStore.flashOnDataChange]; defaults to `false` so
 * production renders untouched.
 */
val LocalFlashOnDataChange = staticCompositionLocalOf { false }

/** Amber so the flash reads as "data" rather than an error or success. */
private val FLASH_COLOR = Color(0xFFFFC107)

/**
 * A stable string fingerprint of the states a card depends on, used to detect "this card's data
 * changed" across snapshots. Built from the primary `state` of each referenced entity only —
 * deliberately not the full [ee.schimke.ha.model.EntityState], whose `lastUpdated` churns on every
 * poll and would flash cards that didn't visibly change. Matches the change definition the debug
 * Logs view uses.
 */
internal fun cardStateSignature(snapshot: HaSnapshot, refs: Set<String>): String =
  refs.joinToString(";") { id -> "$id=${snapshot.states[id]?.state}" }

/**
 * Overlay drawn inside a [CardSlot]'s slot [Box] that flashes whenever [signature] changes. The
 * first observed signature seeds silently (no flash on initial appearance); subsequent changes snap
 * the highlight to full and fade it out. A no-op until a real change arrives, so it costs only a
 * remembered [Animatable] when nothing is moving.
 */
@Composable
internal fun BoxScope.CardChangeFlash(signature: String) {
  val flash = remember { Animatable(0f) }
  var previous by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(signature) {
    val before = previous
    previous = signature
    if (before != null && before != signature) {
      flash.snapTo(1f)
      flash.animateTo(targetValue = 0f, animationSpec = tween(durationMillis = 850))
    }
  }
  val alpha = flash.value
  if (alpha > 0f) {
    val shape = RoundedCornerShape(12.dp)
    Box(
      modifier =
        Modifier.matchParentSize()
          .clip(shape)
          .background(FLASH_COLOR.copy(alpha = 0.28f * alpha))
          .border(2.dp, FLASH_COLOR.copy(alpha = alpha), shape)
    )
  }
}

/**
 * Floating debug panel listing the live data the current dashboard's cards consume: one row per
 * referenced entity with its id (key), current `state` (value), and how long ago that value last
 * changed.
 *
 * Last-changed is tracked locally — the first value seen seeds silently, and the wall-clock is
 * stamped each time a subsequent snapshot reports a different state — falling back to HA's own
 * `last_changed` when the app hasn't yet witnessed a change. This works in demo mode (which
 * animates values but may not carry `last_changed`) as well as live.
 *
 * The panel floats top-centre and only intercepts touches within its own bounds, so the dashboard
 * underneath stays scrollable. [contentPadding] is the host scaffold's inset (status bar / top app
 * bar) so the panel lands below the chrome rather than under it. [onClose] is wired to the Settings
 * toggle so the ✕ truly dismisses it.
 */
@Composable
internal fun DataGridOverlay(
  dashboard: Dashboard,
  snapshot: HaSnapshot,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(0.dp),
) {
  val refs = remember(dashboard) { referencedEntities(dashboard).sorted() }
  // Reset the change-tracking when the dashboard swaps so stale times
  // from a previous board don't leak in.
  val changeAt = remember(dashboard) { mutableStateMapOf<String, Long>() }
  val lastValue = remember(dashboard) { HashMap<String, String>() }

  LaunchedEffect(dashboard, snapshot) {
    val now = System.currentTimeMillis()
    refs.forEach { id ->
      val value = snapshot.states[id]?.state ?: return@forEach
      val prior = lastValue.put(id, value)
      if (prior != null && prior != value) changeAt[id] = now
    }
  }

  // 1 Hz tick so the "Xs ago" labels advance without a data change.
  var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(1000)
      nowMs = System.currentTimeMillis()
    }
  }

  Box(modifier = modifier.fillMaxSize().padding(contentPadding)) {
    Surface(
      color = Color(0xE61C1B1F),
      contentColor = Color.White,
      shape = RoundedCornerShape(12.dp),
      modifier =
        Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp).heightIn(max = 360.dp),
    ) {
      Column {
        Row(
          modifier =
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Card data · ${refs.size} entities",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
          )
          IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Hide data grid")
          }
        }
        if (refs.isEmpty()) {
          Text(
            text = "No entities referenced by this dashboard's cards.",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          )
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            items(refs, key = { it }) { id ->
              val entity = snapshot.states[id]
              val changedMs = changeAt[id] ?: entity?.lastChanged?.toEpochMilliseconds()
              val recent = changeAt[id]?.let { nowMs - it < 1500 } ?: false
              DataGridRow(
                key = id,
                value = entity?.state ?: "—",
                ago = changedMs?.let { agoLabel(nowMs - it) } ?: "—",
                highlighted = recent,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DataGridRow(key: String, value: String, ago: String, highlighted: Boolean) {
  val rowColor = if (highlighted) FLASH_COLOR.copy(alpha = 0.22f) else Color.Transparent
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(4.dp))
        .background(rowColor)
        .padding(horizontal = 4.dp, vertical = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = key,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
      color = Color.White.copy(alpha = 0.85f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1.4f),
    )
    Text(
      text = value,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
      color = if (highlighted) FLASH_COLOR else Color.White,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = ago,
      fontFamily = FontFamily.Monospace,
      fontSize = 10.sp,
      color = Color.White.copy(alpha = 0.55f),
      maxLines = 1,
    )
  }
}

private fun agoLabel(deltaMs: Long): String {
  val seconds = (deltaMs / 1000).coerceAtLeast(0)
  return when {
    seconds < 60 -> "${seconds}s ago"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s ago"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m ago"
  }
}
