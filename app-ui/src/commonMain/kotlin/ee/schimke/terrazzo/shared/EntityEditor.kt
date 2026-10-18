package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.EntityState
import kotlinx.serialization.json.*

@Composable
internal fun EntityEditor(
  entity: EntityState,
  enabled: Boolean,
  onService: (String, String, JsonObject) -> Unit,
) {
  val domain = entity.entityId.substringBefore('.')
  val available = enabled && entity.state !in setOf("unknown", "unavailable")
  if (domain in setOf("select", "input_select")) {
    val options =
      (entity.attributes["options"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
    var expanded by remember(entity.entityId) { mutableStateOf(false) }
    Box {
      TextButton(enabled = available && options.isNotEmpty(), onClick = { expanded = true }) {
        Text("Choose")
      }
      DropdownMenu(expanded, { expanded = false }) {
        options.forEach { option ->
          DropdownMenuItem(
            text = { Text(option) },
            onClick = {
              expanded = false
              onService(
                domain,
                "select_option",
                buildJsonObject {
                  put("entity_id", entity.entityId)
                  put("option", option)
                },
              )
            },
          )
        }
      }
    }
  } else {
    var editing by remember(entity.entityId) { mutableStateOf(false) }
    var draft by remember(entity.entityId, entity.state) { mutableStateOf(entity.state) }
    TextButton(enabled = available, onClick = { editing = true }) { Text("Edit") }
    if (editing)
      AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text("Edit message") },
        text = {
          OutlinedTextField(
            draft,
            { draft = it },
            label = { Text("Value") },
            modifier = Modifier.fillMaxWidth().widthIn(max = 400.dp),
          )
        },
        confirmButton = {
          TextButton(
            onClick = {
              onService(
                domain,
                "set_value",
                buildJsonObject {
                  put("entity_id", entity.entityId)
                  put("value", draft)
                },
              )
              editing = false
            }
          ) {
            Text("Save")
          }
        },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
      )
  }
}
