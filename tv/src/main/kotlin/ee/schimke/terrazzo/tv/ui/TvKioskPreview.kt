package ee.schimke.terrazzo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import kotlinx.coroutines.delay

/**
 * A miniature kiosk dashboard that re-skins itself live as the picker selection changes. Its
 * headline row is real Home Assistant cards ([TvKioskCards]) in the chosen card style. When
 * [demoMode] is on the values come from [TvDemoData] and tick on a 2 s cadence so the room sees
 * motion; when off, the cards show their entities as unavailable and the tiles a static "—" until
 * full HA wiring lands.
 *
 * [frozenNowMs] pins the clock to a fixed instant for `@Preview` renders so the PNG is byte-stable
 * — sine-driven values in `TvDemoData.tiles` would otherwise drift between runs. Production callers
 * leave it null and the tiles tick on a 2 s cadence off the wall clock. Gating this on
 * `LocalInspectionMode.current` was the previous approach but Robolectric-based preview renderers
 * leave inspection mode off (see the compose-preview skill's a11y reference), so the previews
 * picked up wall-clock values; an explicit parameter side-steps the inspection-mode contract
 * entirely.
 */
@Composable
fun TvKioskPreview(
  style: ThemeStyle,
  demoMode: Boolean,
  modifier: Modifier = Modifier,
  frozenNowMs: Long? = null,
  cardStyle: HaUberTheme = HaUberTheme.Classic,
) {
  // Classic draws in the theme's Material roles. Any other card style re-skins the kiosk in its
  // own palette (dark: a TV is), corners and reading weight, and inks its readings when it is
  // monochrome — the same look its dashboard cards have.
  val cs = MaterialTheme.colorScheme.withCardStyle(cardStyle)
  val shape = if (cardStyle.id == HaUberTheme.Classic.id) null else cardStyle.shape
  val chipCorner = shape?.let { (it.cornerDp * 0.7f).dp } ?: 14.dp
  val valueWeight = if (shape == null) null else cardStyle.valueWeight
  val ink = if (shape == null) null else cardStyle.inkFor(darkTheme = true)

  var nowMs by remember { mutableLongStateOf(frozenNowMs ?: System.currentTimeMillis()) }
  LaunchedEffect(demoMode, frozenNowMs) {
    if (!demoMode || frozenNowMs != null) return@LaunchedEffect
    while (true) {
      nowMs = System.currentTimeMillis()
      delay(2_000)
    }
  }

  val tiles = if (demoMode) TvDemoData.tiles(nowMs) else PLACEHOLDER_TILES

  Column(
    modifier =
      modifier
        .fillMaxHeight()
        .clip(RoundedCornerShape(if (shape == null) 24.dp else (shape.cornerDp * 1.5f).dp))
        .background(cs.surfaceContainerLow)
        .padding(28.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "Living room",
          style = MaterialTheme.typography.headlineMedium,
          color = cs.onSurface,
        )
        if (demoMode) {
          androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
          Box(
            modifier =
              Modifier.clip(RoundedCornerShape(8.dp))
                .background(cs.tertiary.copy(alpha = 0.18f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
          ) {
            Text(text = "DEMO", style = MaterialTheme.typography.labelMedium, color = cs.tertiary)
          }
        }
      }
      Text(
        text = "Preview · ${style.displayName}" + if (demoMode) " · animated" else "",
        style = MaterialTheme.typography.titleSmall,
        color = cs.onSurfaceVariant,
      )
    }

    // The headline row is real HA cards in the card style (TvKioskCards); the rest of the
    // demo values sit in a secondary band below.
    TvKioskCards(
      cardStyle = cardStyle,
      style = style,
      snapshot = if (demoMode) TvDemoData.snapshot(nowMs) else TvDemoData.placeholderSnapshot(),
    )
    if (tiles.size > 3) {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.drop(3).forEach { tile ->
          Box(
            modifier =
              Modifier.clip(RoundedCornerShape(chipCorner))
                .background(cs.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp)
          ) {
            Column {
              Text(
                text = tile.label,
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
              )
              Text(
                text = tile.value,
                style = MaterialTheme.typography.bodyLarge,
                color = ink ?: cs.onSurface,
                fontWeight = valueWeight,
              )
            }
          }
        }
      }
    }

    SwatchRow()
  }
}

private data class PlaceholderTile(val label: String, val value: String)

private val PLACEHOLDER_TILES =
  listOf(
    TvDemoData.Tile("Lights", "—"),
    TvDemoData.Tile("Climate", "—"),
    TvDemoData.Tile("Media", "—"),
  )

// Fixed wall-clock instant the `@Preview` entries pass via
// [TvKioskPreview]'s `frozenNowMs` so `TvDemoData`'s sine-driven values
// land on the same numbers every run. 2024-01-01T12:00:00Z — not 0L,
// since the lights/media indices land on the same bucket that
// production demo mode is most often in.
internal const val TV_PREVIEW_NOW_MS: Long = 1_704_110_400_000L

/**
 * These roles re-skinned in [style]'s own dark palette, for every style but Classic (which keeps
 * them): surfaces from its dashboard and card backgrounds, text from its text roles, and the three
 * tile containers from its card surface — a styled kiosk's tiles are its cards.
 */
private fun ColorScheme.withCardStyle(style: HaUberTheme): ColorScheme {
  if (style.id == HaUberTheme.Classic.id) return this
  val p = style.palette(darkTheme = true)
  return copy(
    surfaceContainerLow = p.dashboardBackground,
    surfaceContainerHigh = p.cardBackground,
    onSurface = p.primaryText,
    onSurfaceVariant = p.secondaryText,
    primaryContainer = p.cardBackground,
    onPrimaryContainer = p.primaryText,
    secondaryContainer = p.cardBackground,
    onSecondaryContainer = p.primaryText,
    tertiaryContainer = p.cardBackground,
    onTertiaryContainer = p.primaryText,
    tertiary = p.linkText,
  )
}

@Composable
private fun SwatchRow() {
  val cs = MaterialTheme.colorScheme
  val swatches =
    listOf(
      "primary" to cs.primary,
      "secondary" to cs.secondary,
      "tertiary" to cs.tertiary,
      "surface" to cs.surface,
      "outline" to cs.outline,
    )
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(text = "Roles", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      swatches.forEach { (name, color) ->
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(color))
          Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
          )
        }
      }
    }
  }
}
