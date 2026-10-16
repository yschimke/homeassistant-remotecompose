@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable

/**
 * Alpha an unavailable entity's icon, chip and text draw at: visibly "not live" next to its
 * neighbours, still legible enough to read which entity it is.
 */
const val UnavailableContentAlpha: Float = 0.5f

/**
 * The neutral accent for an entity's resting state (a switch that is off, a motion sensor that sees
 * nothing) under the current theme: the theme's secondary text colour, softened to
 * [NeutralAccentAlpha] so "off" reads as quiet grey (HA's `--state-inactive-color`) rather than as
 * a state colour or as body text. Converters pass it as [HaToggleAccent.inactiveAccent] for every
 * state without an alert colour of its own.
 */
@Composable
fun haNeutralAccent(): RemoteColor {
  val secondary = haTheme().secondaryText
  return secondary.copy(alpha = secondary.alpha * NeutralAccentAlpha.rf)
}

/** How much of the theme's secondary text colour [haNeutralAccent] keeps. */
const val NeutralAccentAlpha: Float = 0.6f

/**
 * How one entity's icon, chip and text look, from its on/off and availability bindings. Shared by
 * the entity row, tile, glance cell and button so an entity reads the same everywhere:
 * * **available** — the on/off accent ([isOn] selecting active vs inactive), content at full alpha;
 * * **unavailable / unknown / missing** — a neutral accent, and every colour [dim]med to
 *   [UnavailableContentAlpha].
 *
 * Both come from named bindings (`<id>.is_on`, `<id>.is_available`), so a live player restyles the
 * entity when it goes offline or comes back exactly as it flips the on/off accent today. With no
 * entity id there is no binding and the authoring-time values are baked.
 */
internal class HaEntityLook(
  /** `<id>.is_on`, for toggle chrome; null when the entity has no on/off state. */
  val isOn: RemoteBoolean?,
  /** `<id>.is_available`, or null when availability is baked ([staticallyAvailable]). */
  private val isAvailable: RemoteBoolean?,
  private val staticallyAvailable: Boolean,
  /** The neutral colour that replaces the on/off accent while unavailable. */
  private val neutral: RemoteColor,
  onOff: RemoteColor,
  themedActive: RemoteColor,
  themedInactive: RemoteColor,
) {
  /**
   * [color] while the entity is available, [color] at [UnavailableContentAlpha] otherwise.
   *
   * A `select` between the colour and a constant-alpha copy, not a copy with a bound alpha: a host
   * that bakes the bindings to constants (launcher and Wear widgets) folds the `select` away, where
   * an alpha expression over a theme colour does not survive the bake.
   */
  fun dim(color: RemoteColor): RemoteColor =
    when {
      isAvailable != null -> isAvailable.select(color, faded(color))
      staticallyAvailable -> color
      else -> faded(color)
    }

  /**
   * [onOff] while the entity is available, the (dimmed) neutral accent otherwise: for a caller that
   * builds its own on/off accent (an animated tween) instead of using [accent].
   */
  fun available(onOff: RemoteColor): RemoteColor =
    when {
      isAvailable != null -> isAvailable.select(onOff, faded(neutral))
      staticallyAvailable -> onOff
      else -> faded(neutral)
    }

  /**
   * Icon tint / chip colour: the on/off accent, or the dimmed neutral accent while unavailable.
   * Already dimmed; don't [dim] it again.
   */
  val accent: RemoteColor = available(onOff)

  /** Themed active accent, dimmed while unavailable (toggle-switch track). */
  val activeAccent: RemoteColor = dim(themedActive)

  /** Themed inactive accent, dimmed while unavailable (toggle-switch track). */
  val inactiveAccent: RemoteColor = dim(themedInactive)

  private fun faded(color: RemoteColor): RemoteColor =
    color.copy(alpha = color.alpha * UnavailableContentAlpha.rf)
}

/**
 * The [HaEntityLook] for [entityId] with [accent]. [live] false resolves both bindings from the
 * authoring-time values instead of registering them — for a state-layout sibling tier that must not
 * re-register a named `RemoteBoolean` (see `RemoteHaIconChip`).
 */
@Composable
internal fun entityLook(
  entityId: String?,
  accent: HaToggleAccent,
  live: Boolean = true,
): HaEntityLook {
  val themed = accent.themed()
  val isOn = if (live && accent.toggleable) LiveValues.isOn(entityId, accent.initiallyOn) else null
  val onOff: RemoteColor =
    when {
      isOn != null -> isOn.select(themed.activeAccent, themed.inactiveAccent)
      !live && accent.toggleable && !accent.initiallyOn -> themed.inactiveAccent
      else -> themed.activeAccent
    }
  return HaEntityLook(
    isOn = isOn,
    isAvailable = if (live) LiveValues.isAvailable(entityId, accent.initiallyAvailable) else null,
    staticallyAvailable = accent.initiallyAvailable,
    neutral = unavailableAccent(),
    onOff = onOff,
    themedActive = themed.activeAccent,
    themedInactive = themed.inactiveAccent,
  )
}

/** Neutral colour of an unavailable entity: the uber theme's faint ink, else [haNeutralAccent]. */
@Composable
private fun unavailableAccent(): RemoteColor =
  currentHaUberTheme().ink?.copy(alpha = 0.35f)?.rc ?: haNeutralAccent()
