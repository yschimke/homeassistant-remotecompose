package ee.schimke.ha.rc.icons.mdi

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap

/**
 * The Material Design Icons (Pictogrammers, Apache-2.0) that harc bundles, as [ImageVector]s.
 *
 * Home Assistant names its icons in MDI (`mdi:thermometer`), so cards draw the same glyph HA does.
 * Only a subset ships ([names], generated into [MdiPaths] by `scripts/generate-mdi-icons.py`): the
 * icons Home Assistant uses as entity defaults, every icon the bundled dashboards and fixtures
 * reference, and a curated list of common picks. Each glyph is one filled path on a 24×24 viewport,
 * black like the Material icons, so callers tint it the same way.
 */
object MdiIcons {
  /** The bundled MDI release. */
  const val VERSION: String = MdiPaths.VERSION

  /** Every bundled icon name (no `mdi:` prefix), sorted. */
  val names: List<String>
    get() = MdiPaths.names

  private val built = ConcurrentHashMap<String, ImageVector>()

  /** Whether [name] (no `mdi:` prefix) is bundled. */
  operator fun contains(name: String): Boolean = MdiPaths.path(name) != null

  /** The glyph for [name] (no `mdi:` prefix), or null when it isn't bundled. */
  operator fun get(name: String): ImageVector? {
    val path = MdiPaths.path(name) ?: return null
    return built.computeIfAbsent(name) { build(it, path) }
  }

  private fun build(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = "mdi:$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
      )
      .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
      .build()
}
