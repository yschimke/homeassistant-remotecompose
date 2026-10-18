package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Container cards describe grouping, not another outline around every child. */
@Composable
internal fun CardCluster(
  card: CardConfig,
  snapshot: HaSnapshot,
  renderLeaf: @Composable (CardConfig) -> Unit,
) {
  val shown = visibleDashboardCard(card, snapshot) ?: return
  val children =
    childCards(shown).mapIndexedNotNull { index, child ->
      visibleDashboardCard(child, snapshot)?.let { index to it }
    }
  if (shown.type !in setOf("grid", "vertical-stack", "horizontal-stack")) {
    renderLeaf(shown)
    return
  }
  if (children.isEmpty()) return
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val requested = (shown.raw["columns"] as? JsonPrimitive)?.intOrNull ?: children.size
    val columns =
      if (shown.type == "vertical-stack") 1
      else
        ((maxWidth.value + 12) / 160)
          .toInt()
          .coerceAtLeast(1)
          .coerceAtMost(requested.coerceIn(1, 6))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      children.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          row.forEach { (index, child) ->
            key(index) { Box(Modifier.weight(1f)) { CardCluster(child, snapshot, renderLeaf) } }
          }
          repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
      }
    }
  }
}
