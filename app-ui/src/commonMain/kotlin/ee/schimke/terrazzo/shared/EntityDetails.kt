package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.formatState
import kotlinx.serialization.json.*

/** Observe secondary presses before RC children consume their pointer events. */
internal fun Modifier.entityDetails(
  entityId: String?,
  onMoreInfo: ((String) -> Unit)?,
): Modifier =
  if (entityId == null || onMoreInfo == null) this
  else
    composed {
      val currentCallback by rememberUpdatedState(onMoreInfo)
      pointerInput(entityId) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (
              event.type == PointerEventType.Press &&
                event.buttons.isSecondaryPressed &&
                event.changes.none { it.isConsumed }
            ) {
              event.changes.forEach { it.consume() }
              suppressNextEntityContextMenu()
              currentCallback(entityId)
            }
          }
        }
      }
    }

internal expect fun suppressNextEntityContextMenu()

@Composable
internal fun EntityDetailsDialog(entityId: String, snapshot: HaSnapshot, onDismiss: () -> Unit) {
  val entity = snapshot.states[entityId]
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(entity?.attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull ?: entityId)
    },
    text = {
      Column(
        Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(entityId)
        Text(formatState(entity, snapshot), style = MaterialTheme.typography.headlineSmall)
        entity?.lastUpdated?.let { Text("Last updated: $it") }
        entity?.attributes?.forEach { (name, value) ->
          Text("$name: ${if (value is JsonPrimitive) value.content else value.toString()}")
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}
