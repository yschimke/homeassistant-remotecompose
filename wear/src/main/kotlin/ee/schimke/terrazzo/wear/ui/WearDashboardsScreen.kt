package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.EntityValue

/**
 * Browse-mode list — one row per dashboard the phone published. Tap a row to drill into
 * [WearDashboardScreen].
 */
@Composable
fun WearDashboardsScreen(
  dashboards: List<DashboardData>,
  onDashboardPicked: (DashboardData) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  WearListScreen(modifier = modifier) {
    item { ListHeader { Text("Dashboards") } }

    if (dashboards.isEmpty()) {
      item {
        Box(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "Waiting for the phone to publish dashboards…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      items(dashboards) { dashboard ->
        Button(
          onClick = { onDashboardPicked(dashboard) },
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.filledTonalButtonColors(),
        ) {
          Column {
            Text(
              text = dashboard.title.ifEmpty { dashboard.urlPath.ifEmpty { "Default" } },
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text = "${dashboard.cards.size} card${if (dashboard.cards.size == 1) "" else "s"}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }

    item {
      Button(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
      ) {
        Text("Back")
      }
    }
  }
}

/** Single-dashboard drilldown — play every card, including layout containers and their children. */
@Composable
fun WearDashboardScreen(
  dashboard: DashboardData,
  values: Map<String, EntityValue>,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  onInputSend: (suspend (String, Map<String, String>) -> Unit)? = null,
) {
  WearListScreen(modifier = modifier) {
    item { ListHeader { Text(dashboard.title.ifEmpty { "Dashboard" }) } }

    if (dashboard.cards.isEmpty()) {
      item {
        Text(
          text = "Empty dashboard",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
      }
    } else {
      items(dashboard.cards) { card ->
        if (card.type == ee.schimke.ha.model.MESSAGE_CARD_TYPE) {
          WearMessageCard(card.rawJson, values, onInputSend)
        } else {
          WearRemoteCard(card, values)
        }
      }
    }

    item {
      Button(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
      ) {
        Text("Back")
      }
    }
  }
}
