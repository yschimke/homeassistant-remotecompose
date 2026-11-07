package ee.schimke.terrazzo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ee.schimke.terrazzo.tv.data.TvDesign

/**
 * TV-side design picker: the TV's one theme choice, a short list of designs ([TvDesign.all], plus
 * the current one if an earlier build picked another). Vertical column of focusable rows; the row's
 * border lights up while focused, the row's surface fills with `primaryContainer` while selected.
 * The first row grabs initial focus so the remote's DPAD has a starting point.
 */
@Composable
fun TvDesignPicker(
  selected: TvDesign,
  onSelect: (TvDesign) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      text = "Design",
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onBackground,
    )
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    val designs = TvDesign.all.let { if (selected in it) it else it + selected }
    designs.forEachIndexed { index, design ->
      PickerRow(
        title = design.title,
        subtitle = design.subtitle,
        selected = design == selected,
        onSelect = { onSelect(design) },
        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
      )
    }
  }
}

@Composable
private fun PickerRow(
  title: String,
  subtitle: String?,
  selected: Boolean,
  onSelect: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val interaction = remember { MutableInteractionSource() }
  val focused by interaction.collectIsFocusedAsState()
  val containerColor =
    if (selected) {
      MaterialTheme.colorScheme.primaryContainer
    } else {
      MaterialTheme.colorScheme.surfaceContainer
    }
  val contentColor =
    if (selected) {
      MaterialTheme.colorScheme.onPrimaryContainer
    } else {
      MaterialTheme.colorScheme.onSurface
    }
  val borderColor = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .background(containerColor, RoundedCornerShape(16.dp))
        .border(3.dp, borderColor, RoundedCornerShape(16.dp))
        .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
        .padding(horizontal = 20.dp, vertical = 14.dp),
    contentAlignment = Alignment.CenterStart,
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = contentColor,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
      )
      if (subtitle != null) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.labelMedium,
          color = contentColor.copy(alpha = 0.75f),
        )
      }
    }
  }
}
