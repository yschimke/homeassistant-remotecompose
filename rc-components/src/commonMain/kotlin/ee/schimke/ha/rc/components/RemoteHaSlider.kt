@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.action.valueChange
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.contentDescription
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.onTouchDown
import androidx.compose.remote.creation.compose.modifier.onTouchUp
import androidx.compose.remote.creation.compose.modifier.semantics
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.max
import androidx.compose.remote.creation.compose.state.min
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rememberMutableRemoteInt
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.ri
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.PaintingStyle

/**
 * What a [RemoteHaSlider] shows and sends.
 *
 * [action] is the release's [HaAction.SetValue] as drawn: its `ticks` is where the slider rests now
 * ([HaAction.SetValue.from]). [label] names the value for a screen reader ("Brightness", or empty
 * where the card already names it); the value is shown as `min + ticks × step` with [decimals]
 * places and [unit] ("62 %"). An [available] false slider (its entity is offline) draws muted and
 * doesn't drag.
 */
data class HaSliderSpec(
  val action: HaAction.SetValue,
  val label: String,
  val unit: String = "",
  val decimals: Int = 0,
  val available: Boolean = true,
)

/**
 * HA's `ha-control-slider`: a rounded track filled from its start to the value in the tile's
 * [accent], with the value in a bubble riding the fill's edge.
 *
 * Dragging moves the fill and the value with the finger, all inside the document; releasing sends
 * the new value once ([HaAction.SetValue]). The drag is relative, so touching the slider never
 * moves it: only sliding does, and a tap sends the unchanged value, which no host runs.
 *
 * Under a [locked][HaControlLock] card the fill is muted grey (the card's badge shows the lock), a
 * drag doesn't move it, and a touch shows the card's unlock strip. Read-only (a Wear widget, or a
 * player with no drag: [touchControlsSupported]) it draws the value alone, in its usual colours.
 *
 * The value is the one the card was drawn with: a slider card re-encodes when its entity changes (a
 * tile's features are baked, see `TileContent.bakes`), so a release that lands is drawn from the
 * new state rather than animated from the old.
 */
@Composable
@RemoteComposable
fun RemoteHaSlider(
  spec: HaSliderSpec,
  accent: RemoteColor,
  heightDp: Int = TileFeatureHeightDp,
  subject: String = "",
  modifier: RemoteModifier = RemoteModifier,
) {
  val theme = haTheme()
  val action = spec.action
  val lock = haControlLock()
  val interactive =
    touchControlsSupported && spec.available && action.maxTicks > 0 && (lock?.canUnlock ?: true)
  val maxTicks = action.maxTicks.coerceAtLeast(1)
  val muted = theme.secondaryText.copy(alpha = theme.secondaryText.alpha * 0.45f.rf)
  val fill: RemoteColor =
    when {
      !spec.available -> muted
      lock != null -> lock.color(accent, muted)
      else -> accent
    }
  val spokenLabel = listOf(subject, spec.label).filter { it.isNotEmpty() }.joinToString(", ")

  val ticks: RemoteFloat
  val canvasModifier: RemoteModifier
  val define: (RemoteDrawScope.() -> Unit)?
  if (interactive) {
    val touchSlot = ReservedId("slider")
    val payloadSlot = ReservedId("slider-payload")
    ticks = reservedFloat(touchSlot)
    val (release, definePayload) =
      sliderRemoteAction(action, ticks.toPaddedRemoteString(1), payloadSlot)
    // A touch-down handler makes the canvas the component that takes the touch, so its release
    // runs; on a locked card it also shows the unlock strip.
    val down = lock?.touched ?: valueChange(rememberMutableRemoteInt(0), 1.ri)
    canvasModifier = RemoteModifier.fillMaxSize().onTouchDown(down).onTouchUp(release)
    define = {
      defineTouch(
        touchSlot,
        initial = action.from.toFloat(),
        min = 0f,
        max = maxTicks.toFloat(),
        unitsPerPx = maxTicks.toFloat().rf / max(width, 1f),
        gate = lock?.unlocked,
        stopAtEnds = false,
      )
      definePayload()
    }
  } else {
    ticks = action.from.toFloat().rf
    canvasModifier = RemoteModifier.fillMaxSize()
    define = null
  }
  val fraction = ticks / maxTicks.toFloat().rf
  val shown: RemoteFloat = action.min.toFloat().rf + ticks * action.step.toFloat().rf
  val valueText: RemoteString =
    shown.toDecimalRemoteString(spec.decimals) + (if (spec.unit.isEmpty()) "" else " ${spec.unit}")

  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .height(heightDp.rdp)
        .clip(haControlShape(SliderCornerDp))
        .background(sliderTrack(theme.secondaryText))
        .semantics { contentDescription = spokenLabel.rs }
  ) {
    RemoteCanvas(modifier = canvasModifier) {
      define?.invoke(this)
      val edge = width * fraction
      drawRect(
        RemotePaint { style = PaintingStyle.Fill }.apply { color = fill },
        RemoteOffset(0f.rf, 0f.rf),
        RemoteSize(edge, height),
      )
      // The value rides the fill's edge in a bubble in the card's colour: under the finger while it
      // drags, and readable on any fill. Kept inside the track at either end.
      val inset = height * 0.12f.rf
      val bubbleHeight = height - inset * 2f.rf
      val bubbleWidth = height * BubbleWidthPerHeight.rf
      val half = bubbleWidth / 2f.rf
      val centre = min(max(edge, inset + half), width - inset - half)
      drawRoundRect(
        RemotePaint { style = PaintingStyle.Fill }.apply { color = theme.cardBackground },
        RemoteOffset(centre - half, inset),
        RemoteSize(bubbleWidth, bubbleHeight),
        RemoteOffset(bubbleHeight / 2f.rf, bubbleHeight / 2f.rf),
      )
      drawAnchoredText(
        valueText,
        centre,
        height / 2f.rf,
        RemotePaint {
          style = PaintingStyle.Fill
          textSize = height * 0.36f.rf
        }
          .apply {
            color =
              when {
                !spec.available -> theme.secondaryText
                lock != null -> lock.color(theme.primaryText, theme.secondaryText)
                else -> theme.primaryText
              }
          },
      )
    }
  }
}

/**
 * The value bubble's width, in track heights: room for `100 %` or `22.5 °C` at the text size the
 * bubble draws.
 */
private const val BubbleWidthPerHeight = 2.2f

/** HA's `--feature-border-radius` (12px), as the other tile features. */
private const val SliderCornerDp = 12

/** HA's control track: the disabled colour softened, as `featureTrack` in the tile features. */
private fun sliderTrack(secondary: RemoteColor): RemoteColor =
  secondary.copy(alpha = secondary.alpha * 0.18f.rf)
