package ee.schimke.ha.rc.icons.mdi

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import io.github.timoptr.mdiicons.Mdi
import io.github.timoptr.mdiicons.MdiIcon
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Material Design Icons (Pictogrammers, Apache-2.0) as [ImageVector]s, for Remote Compose documents
 * to bake with `toRemoteImageVector()`.
 *
 * Home Assistant names its icons in MDI (`mdi:thermometer`), so cards draw the same glyph HA does.
 * The whole catalog resolves, through `io.github.timoptr:mdi-icons` ([Mdi]): canonical names, the
 * catalog's aliases, and the historical renames older Home Assistant configurations still carry.
 * The library loads only the alphabetical chunk holding a name it is asked for. Each glyph is one
 * filled path on a 24×24 viewport, black like the Material icons, so callers tint it the same way.
 */
@OptIn(ExperimentalAtomicApi::class)
object MdiIcons {
  /** Built glyphs by requested name. Copy-on-write: a racing first use builds a glyph twice. */
  private val built = AtomicReference(emptyMap<String, ImageVector>())

  /** Forgets the built glyphs; each rebuilds from its path data on next use. */
  fun releaseMemory() {
    built.store(emptyMap())
  }

  /**
   * The MDI name a Home Assistant icon string names — `mdi:water-percent`, the legacy `hass:fan`,
   * or a bare `weather-sunny` — trimmed, lower-cased and without its prefix; null for a blank
   * string or another icon set (`phu:`, `si:`, …). It need not exist: see [contains].
   */
  fun nameOf(raw: String?): String? {
    val trimmed = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
      trimmed.startsWith("mdi:") -> trimmed.removePrefix("mdi:")
      trimmed.startsWith("hass:") -> trimmed.removePrefix("hass:")
      ':' in trimmed -> null
      else -> trimmed
    }?.takeIf { it.isNotEmpty() }
  }

  /** Whether [name] (no `mdi:` prefix) is an MDI icon. */
  operator fun contains(name: String): Boolean = Mdi.fromMdiName(name) != null

  /** The catalog entry for [name] (no `mdi:` prefix), or null when MDI has no such icon. */
  fun icon(name: String): MdiIcon? = Mdi.fromMdiName(name)

  /** The glyph for [name] (no `mdi:` prefix), or null when MDI has no such icon. */
  operator fun get(name: String): ImageVector? {
    built.load()[name]?.let {
      return it
    }
    val icon = Mdi.fromMdiName(name) ?: return null
    return vector(icon, name)
  }

  /** The glyph for [icon], a compile-time accessor such as `Mdi.HomeAssistant`. */
  operator fun get(icon: MdiIcon): ImageVector = built.load()[icon.name] ?: vector(icon, icon.name)

  private fun vector(icon: MdiIcon, key: String): ImageVector {
    val vector = build(icon)
    while (true) {
      val current = built.load()
      current[key]?.let {
        return it
      }
      if (built.compareAndSet(current, current + (key to vector))) return vector
    }
  }

  private fun build(icon: MdiIcon): ImageVector =
    ImageVector.Builder(
        name = "mdi:${icon.name}",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
      )
      .addPath(pathData = addPathNodes(icon.pathData), fill = SolidColor(Color.Black))
      .build()
}
