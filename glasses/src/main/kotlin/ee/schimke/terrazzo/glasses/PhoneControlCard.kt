package ee.schimke.terrazzo.glasses

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.room.RoomEntity
import ee.schimke.terrazzo.room.TOGGLE_DOMAINS

/** One control on the phone: its name and state, and a switch for one that turns on and off. */
@Composable
fun PhoneControlCard(
  entity: RoomEntity,
  snapshot: HaSnapshot,
  onToggle: (entityId: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val state = snapshot.states[entity.entityId]
  Card(modifier.fillMaxWidth()) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(entity.name, style = MaterialTheme.typography.titleMedium)
        Text(
          state?.let { formatState(it, snapshot) } ?: "Unavailable",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (state != null && entity.domain in TOGGLE_DOMAINS) {
        Switch(
          checked = HaLiveBindings.isOn(entity.entityId, state.state),
          onCheckedChange = { onToggle(entity.entityId) },
          modifier = Modifier.semantics { contentDescription = entity.name },
        )
      }
    }
  }
}
