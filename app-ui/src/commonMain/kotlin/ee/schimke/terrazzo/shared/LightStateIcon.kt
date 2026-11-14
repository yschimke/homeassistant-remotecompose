package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.icons.mdi.MdiIcons
import io.github.timoptr.mdiicons.Mdi
import io.github.timoptr.mdiicons.generated.LightbulbOn
import io.github.timoptr.mdiicons.generated.LightbulbOutline

/** A light's state is semantic: a lit bulb with rays, or an unlit outline, in every card style. */
@Composable
internal fun LightStateIcon(state: String?) {
  val colors = MaterialTheme.colorScheme
  Icon(
    imageVector = MdiIcons[if (state == "on") Mdi.LightbulbOn else Mdi.LightbulbOutline],
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
