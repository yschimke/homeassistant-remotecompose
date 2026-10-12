package ee.schimke.ha.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The icons an `ha-cards` design names by key. A Remote icon is an `ImageVector` captured into the
 * document, which no design value can spell, so the wrappers take one of these keys instead: the
 * `icon` property's allowed values in `ui-builder.policy.json` are exactly [keys], and
 * `HaCardIconsTest` holds the two together.
 *
 * Keys are Home Assistant's own domain vocabulary where one fits (`light`, `switch`, `climate`, …),
 * so a template seeded from a card can pick its icon from the entity's domain.
 */
object HaCardIcons {
  /** The fallback for an unknown key: HA's generic entity glyph. */
  const val DEFAULT = "home"

  private val icons: Map<String, ImageVector> =
    linkedMapOf(
      "home" to Icons.Filled.Home,
      "light" to Icons.Filled.Lightbulb,
      "switch" to Icons.Filled.PowerSettingsNew,
      "climate" to Icons.Filled.Thermostat,
      "sensor" to Icons.Filled.Sensors,
      "fan" to Icons.Filled.Air,
      "lock" to Icons.Filled.Lock,
      "door" to Icons.Filled.MeetingRoom,
      "power" to Icons.Filled.Bolt,
      "water" to Icons.Filled.WaterDrop,
      "media" to Icons.Filled.Tv,
      "alert" to Icons.Filled.Notifications,
    )

  /** Every key a design may write, in the order the property editor offers them. */
  val keys: List<String> = icons.keys.toList()

  /** The glyph for [key], or [DEFAULT]'s when the key is unknown. */
  fun vector(key: String): ImageVector = icons[key] ?: icons.getValue(DEFAULT)
}
