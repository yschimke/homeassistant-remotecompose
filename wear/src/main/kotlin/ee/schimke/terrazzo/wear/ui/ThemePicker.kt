package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.Text
import ee.schimke.ha.rc.components.ThemeStyle

/**
 * One-line theme picker for the watch: a [ScalingLazyColumn] of radio
 * rows (one per [ThemeStyle]) with the active palette name in the
 * header. Selecting a row calls [onSelect] which the caller writes to
 * [ee.schimke.terrazzo.wear.data.WearPrefs].
 */
@Composable
fun WearThemePicker(
    selected: ThemeStyle,
    onSelect: (ThemeStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            ListHeader { Text("Terrazzo theme") }
        }
        item {
            Text(
                text = selected.tagline,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        items(ThemeStyle.entries) { style ->
            RadioButton(
                selected = style == selected,
                onSelect = { onSelect(style) },
                label = {
                    Text(
                        text = style.displayName,
                        fontWeight = if (style == selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
