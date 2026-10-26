package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.components.HaAction
import kotlin.math.roundToInt

/**
 * The [controls] of an entity's more-info sheet in a panel, each input sent through [onAction] (the
 * dashboard's dispatcher, so read-only mode, demo mode, haptics and the action log apply as to a
 * card's own tap). With [readOnly] the controls show the current values, disabled, under a note
 * saying why.
 *
 * A slider follows the entity's value until it is dragged, then holds where it was let go until
 * Home Assistant reports the new value, so it doesn't jump back while the call is in flight.
 */
@Composable
internal fun EntityControlsPanel(
  controls: List<EntityControl>,
  accent: Color,
  readOnly: Boolean,
  onAction: (HaAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (controls.isEmpty()) return
  val skin = currentScreenSkin()
  SkinPanel(modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      if (readOnly) {
        Text(
          skin.label("Read-only mode is on: turn it off to control devices"),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      controls.forEach { control ->
        when (control) {
          is EntityControl.Power -> PowerRow(control, accent, !readOnly, onAction)
          is EntityControl.Level -> LevelRow(control, accent, !readOnly, onAction)
          is EntityControl.Stepper -> StepperRow(control, accent, !readOnly, onAction)
          is EntityControl.Buttons -> ButtonsRow(control, !readOnly, onAction)
          is EntityControl.Choice -> ChoiceRow(control, !readOnly, onAction)
        }
      }
    }
  }
}

@Composable
private fun PowerRow(
  control: EntityControl.Power,
  accent: Color,
  enabled: Boolean,
  onAction: (HaAction) -> Unit,
) {
  val skin = currentScreenSkin()
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(
      skin.label(if (control.on) "On" else "Off"),
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.weight(1f),
    )
    Switch(
      checked = control.on,
      onCheckedChange = { onAction(if (it) control.turnOn else control.turnOff) },
      enabled = enabled,
      colors = SwitchDefaults.colors(checkedTrackColor = accent),
      modifier = Modifier.semantics { contentDescription = "Power" },
    )
  }
}

@Composable
private fun LevelRow(
  control: EntityControl.Level,
  accent: Color,
  enabled: Boolean,
  onAction: (HaAction) -> Unit,
) {
  val skin = currentScreenSkin()
  // Where the user let go, until the entity reports a new value.
  var dragged by remember(control.value) { mutableStateOf<Float?>(null) }
  val shown = (dragged ?: control.value ?: control.range.start).coerceIn(control.range)
  Column {
    Row(Modifier.fillMaxWidth()) {
      Text(
        skin.label(control.label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      Text(
        if (control.value == null && dragged == null) "—"
        else formatLevel(shown, control.step, control.unit),
        style = MaterialTheme.typography.labelLarge,
      )
    }
    Slider(
      value = shown,
      onValueChange = { dragged = snapToStep(it, control.range, control.step) },
      onValueChangeFinished = { dragged?.let { onAction(control.set(it)) } },
      valueRange = control.range,
      enabled = enabled,
      colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
      modifier = Modifier.semantics { contentDescription = control.label },
    )
  }
}

@Composable
private fun StepperRow(
  control: EntityControl.Stepper,
  accent: Color,
  enabled: Boolean,
  onAction: (HaAction) -> Unit,
) {
  val skin = currentScreenSkin()
  // The setpoint the last press asked for, until the entity reports one.
  var pending by remember(control.value) { mutableStateOf<Float?>(null) }
  val current = pending ?: control.value
  fun nudge(by: Float) {
    val next = snapToStep((current ?: control.range.start) + by, control.range, control.step)
    pending = next
    onAction(control.set(next))
  }
  Row(
    Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      skin.label(control.label),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f),
    )
    FilledIconButton(
      onClick = { nudge(-control.step) },
      enabled = enabled && (current == null || current > control.range.start),
      shape = skin.badgeShape,
    ) {
      Icon(Icons.Filled.Remove, contentDescription = "Lower ${control.label}")
    }
    Text(
      current?.let { formatLevel(it, control.step, control.unit) } ?: "—",
      style = MaterialTheme.typography.headlineSmall,
      color = skin.ink ?: accent,
    )
    FilledIconButton(
      onClick = { nudge(control.step) },
      enabled = enabled && (current == null || current < control.range.endInclusive),
      shape = skin.badgeShape,
    ) {
      Icon(Icons.Filled.Add, contentDescription = "Raise ${control.label}")
    }
  }
}

@Composable
private fun ButtonsRow(
  control: EntityControl.Buttons,
  enabled: Boolean,
  onAction: (HaAction) -> Unit,
) {
  val skin = currentScreenSkin()
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    control.buttons.forEach { button ->
      FilledTonalButton(
        onClick = { onAction(button.action) },
        enabled = enabled,
        shape = RoundedCornerShape(skin.controlCorner),
        modifier = Modifier.weight(1f),
      ) {
        Icon(
          button.kind.icon,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
          tint = if (button.active) MaterialTheme.colorScheme.primary else Color.Unspecified,
        )
        Spacer8()
        Text(skin.label(button.label), maxLines = 1)
      }
    }
  }
}

@Composable
private fun ChoiceRow(
  control: EntityControl.Choice,
  enabled: Boolean,
  onAction: (HaAction) -> Unit,
) {
  val skin = currentScreenSkin()
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      skin.label(control.label),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      control.options.forEach { option ->
        FilterChip(
          selected = option == control.selected,
          onClick = { if (option != control.selected) onAction(control.choose(option)) },
          enabled = enabled,
          label = { Text(skin.label(displayState(option))) },
          shape = RoundedCornerShape(skin.controlCorner),
        )
      }
    }
  }
}

@Composable private fun Spacer8() = androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))

/** [value] as the control shows it: whole numbers for a whole [step], else one or two decimals. */
internal fun formatLevel(value: Float, step: Float, unit: String?): String {
  val text =
    when {
      step >= 1f && step == step.roundToInt().toFloat() -> value.roundToInt().toString()
      step >= 0.1f -> "%.1f".format(value)
      else -> "%.2f".format(value)
    }
  return when {
    unit == null -> text
    unit == "%" || unit.startsWith("°") -> "$text$unit"
    else -> "$text $unit"
  }
}

private val EntityControl.ButtonKind.icon: ImageVector
  get() =
    when (this) {
      EntityControl.ButtonKind.Open -> Icons.Filled.KeyboardArrowUp
      EntityControl.ButtonKind.Close -> Icons.Filled.KeyboardArrowDown
      EntityControl.ButtonKind.Stop -> Icons.Filled.Stop
      EntityControl.ButtonKind.Play -> Icons.Filled.PlayArrow
      EntityControl.ButtonKind.Pause -> Icons.Filled.Pause
      EntityControl.ButtonKind.Previous -> Icons.Filled.SkipPrevious
      EntityControl.ButtonKind.Next -> Icons.Filled.SkipNext
      EntityControl.ButtonKind.Lock -> Icons.Filled.Lock
      EntityControl.ButtonKind.Unlock -> Icons.Filled.LockOpen
      EntityControl.ButtonKind.Press -> Icons.Filled.TouchApp
      EntityControl.ButtonKind.Dock -> Icons.Filled.Home
    }
