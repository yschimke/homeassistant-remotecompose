package ee.schimke.ha.rc

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The colour a widget container takes from its card's state: warm for heating, cool for cooling,
 * amber for a light that is on, red for a triggered alarm or a problem, green for an armed alarm.
 * The container behind the card is the widget's largest surface, so a tint there says the state
 * before a word is read.
 *
 * Each tint is the state's accent mixed into the container's own dark surface ([base]), a quarter
 * of the way, so light text keeps its contrast on it. Null leaves the container its default.
 */
object ContainerTint {
  /** The container colour Glance Wear fills by default (`surfaceContainerLow`). */
  val base: Color = Color(39, 36, 48)

  private val Heat = Color(0xFFF4511E)
  private val Cool = Color(0xFF039BE5)
  private val Amber = Color(0xFFFFB300)
  private val Alert = Color(0xFFE53935)
  private val Safe = Color(0xFF43A047)

  /** The share of the accent in a tint. */
  private const val MIX = 0.26f

  /** [card]'s container tint, as ARGB, or null for the default container. */
  fun argbFor(card: CardConfig?, snapshot: HaSnapshot): Int? = colorFor(card, snapshot)?.toArgb()

  fun colorFor(card: CardConfig?, snapshot: HaSnapshot): Color? {
    val id = card?.raw?.get("entity")?.jsonPrimitive?.contentOrNull ?: return null
    val entity = snapshot.states[id] ?: return null
    fun attr(key: String) = entity.attributes[key]?.jsonPrimitive?.contentOrNull
    val accent =
      when (id.substringBefore('.')) {
        "climate" ->
          when (attr("hvac_action")) {
            "heating",
            "preheating" -> Heat
            "cooling" -> Cool
            else -> null
          }
        "light" -> if (entity.state == "on") Amber else null
        "alarm_control_panel" ->
          when {
            entity.state == "triggered" -> Alert
            entity.state.startsWith("armed_") -> Safe
            else -> null
          }
        "binary_sensor" ->
          if (
            entity.state == "on" &&
              attr("device_class") in setOf("problem", "smoke", "gas", "moisture", "safety")
          )
            Alert
          else null
        else -> null
      } ?: return null
    return lerp(base, accent, MIX)
  }
}
