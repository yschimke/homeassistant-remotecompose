package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** A light's state is semantic: a lit bulb with rays, or an unlit outline, in every card style. */
@Composable
internal fun LightStateIcon(state: String?) {
  val colors = MaterialTheme.colorScheme
  Icon(
    imageVector = if (state == "on") litBulb else unlitBulb,
    contentDescription = lightIconDescription(state),
    tint = lightIconTint(state, colors.surface, colors.onSurfaceVariant),
    modifier = Modifier.size(24.dp),
  )
}

internal fun lightIconDescription(state: String?): String =
  when (state) {
    "on" -> "Light on"
    "off" -> "Light off"
    "unknown" -> "Light unknown"
    else -> "Light unavailable"
  }

/** Amber stays distinct from chrome, with readable contrast on light and dark card surfaces. */
internal fun lightIconTint(state: String?, surface: Color, neutral: Color): Color =
  if (state == "on") {
    if (surface.luminance() < 0.25f) Color(0xFFFFC857) else Color(0xFF9C5700)
  } else neutral

// The same Apache-2.0 MDI 7.4.47 glyphs bundled by rc-converter's generated MdiPaths.
// Keep this narrow common-UI bridge free of the Android converter dependency.
// Licences: rc-converter/icons/NOTICE.md.
private val litBulb =
  bulbVector(
    "lightbulb-on",
    "M12,6A6,6 0 0,1 18,12C18,14.22 16.79,16.16 15,17.2V19A1,1 0 0,1 14,20H10A1,1 0 0,1 9,19V17.2C7.21,16.16 6,14.22 6,12A6,6 0 0,1 12,6M14,21V22A1,1 0 0,1 13,23H11A1,1 0 0,1 10,22V21H14M20,11H23V13H20V11M1,11H4V13H1V11M13,1V4H11V1H13M4.92,3.5L7.05,5.64L5.63,7.05L3.5,4.93L4.92,3.5M16.95,5.63L19.07,3.5L20.5,4.93L18.37,7.05L16.95,5.63Z",
  )
private val unlitBulb =
  bulbVector(
    "lightbulb-outline",
    "M12,2A7,7 0 0,1 19,9C19,11.38 17.81,13.47 16,14.74V17A1,1 0 0,1 15,18H9A1,1 0 0,1 8,17V14.74C6.19,13.47 5,11.38 5,9A7,7 0 0,1 12,2M9,21V20H15V21A1,1 0 0,1 14,22H10A1,1 0 0,1 9,21M12,4A5,5 0 0,0 7,9C7,11.05 8.23,12.81 10,13.58V16H14V13.58C15.77,12.81 17,11.05 17,9A5,5 0 0,0 12,4Z",
  )

private fun bulbVector(name: String, path: String): ImageVector =
  ImageVector.Builder(
      name = "mdi:$name",
      defaultWidth = 24.dp,
      defaultHeight = 24.dp,
      viewportWidth = 24f,
      viewportHeight = 24f,
    )
    .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
    .build()
