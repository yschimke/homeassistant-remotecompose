package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * What a long-press on a dashboard card offers: fork the card into the card designer, put it on the
 * home screen, or open its history and details. Each action closes the sheet first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardActionsSheet(
  title: String,
  onFork: () -> Unit,
  onAddToHomeScreen: () -> Unit,
  onShowDetails: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    CardActions(
      title = title,
      onFork = {
        onDismiss()
        onFork()
      },
      onAddToHomeScreen = {
        onDismiss()
        onAddToHomeScreen()
      },
      onShowDetails = {
        onDismiss()
        onShowDetails()
      },
    )
  }
}

/** The sheet's list, apart from the sheet so a preview can draw it. */
@Composable
fun CardActions(
  title: String,
  onFork: () -> Unit,
  onAddToHomeScreen: () -> Unit,
  onShowDetails: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxWidth().padding(bottom = 16.dp)) {
    Text(
      title,
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
    Action(
      Icons.Outlined.Brush,
      "Fork in card designer",
      "Restyle a copy with this card's entities and values",
      onFork,
    )
    Action(
      Icons.Outlined.Widgets,
      "Add to home screen",
      "Put this card on your launcher as a widget",
      onAddToHomeScreen,
    )
    Action(
      Icons.Outlined.History,
      "History and details",
      "Recent changes, links and more",
      onShowDetails,
    )
  }
}

@Composable
private fun Action(icon: ImageVector, label: String, supporting: String, onClick: () -> Unit) {
  ListItem(
    headlineContent = { Text(label) },
    supportingContent = { Text(supporting) },
    leadingContent = { Icon(icon, contentDescription = null) },
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
  )
}
