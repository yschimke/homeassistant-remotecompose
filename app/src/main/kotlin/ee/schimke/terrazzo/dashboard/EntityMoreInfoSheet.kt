package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.EntityState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a `more-info` tap opens on the dashboard: the entity's name, state and attributes from the
 * live snapshot, so the values keep up while the sheet is open. [onOpenHistory] (null when there is
 * no Home Assistant to open, as in demo mode) opens the entity's history in the browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityMoreInfoSheet(
  entityId: String,
  state: EntityState?,
  onOpenHistory: (() -> Unit)?,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    EntityMoreInfo(entityId, state, onOpenHistory)
  }
}

/** The sheet's content, apart from the sheet so a preview can draw it. */
@Composable
fun EntityMoreInfo(
  entityId: String,
  state: EntityState?,
  onOpenHistory: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val attributes = state?.attributes.orEmpty()
  val name = attributes["friendly_name"]?.jsonPrimitive?.contentOrNull ?: entityId
  val unit = attributes["unit_of_measurement"]?.jsonPrimitive?.contentOrNull
  Column(modifier.fillMaxWidth().padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
    Text(
      name,
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
    Text(
      state?.let { listOfNotNull(it.state, unit).joinToString(" ") } ?: "Unavailable",
      style = MaterialTheme.typography.headlineSmall,
      modifier = Modifier.padding(horizontal = 24.dp),
    )
    Text(
      entityId,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
    state?.lastChanged?.let { changed ->
      ListItem(headlineContent = { Text("Last changed") }, supportingContent = { Text("$changed") })
    }
    attributes
      .filterKeys { it !in HIDDEN_ATTRIBUTES }
      .forEach { (key, value) ->
        ListItem(
          headlineContent = { Text(key) },
          supportingContent = {
            Text((value as? JsonPrimitive)?.contentOrNull ?: value.toString(), maxLines = 3)
          },
        )
      }
    if (onOpenHistory != null) {
      TextButton(onClick = onOpenHistory, modifier = Modifier.padding(horizontal = 12.dp)) {
        Text("Open history in Home Assistant")
      }
    }
  }
}

/** Attributes the sheet already shows, or that are no use as text. */
private val HIDDEN_ATTRIBUTES =
  setOf("friendly_name", "unit_of_measurement", "icon", "entity_picture", "access_token")
