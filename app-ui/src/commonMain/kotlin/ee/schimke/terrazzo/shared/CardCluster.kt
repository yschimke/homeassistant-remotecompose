package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Container cards describe grouping, not another outline around every child. */
@Composable
internal fun CardCluster(card: CardConfig, renderLeaf: @Composable (CardConfig) -> Unit) {
  val children = childCards(card)
  if (children.isEmpty() || card.type !in setOf("grid", "vertical-stack", "horizontal-stack")) {
    renderLeaf(card)
    return
  }
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val requested = (card.raw["columns"] as? JsonPrimitive)?.intOrNull ?: children.size
    val columns =
      if (card.type == "vertical-stack") 1
      else
        ((maxWidth.value + 12) / 160)
          .toInt()
          .coerceAtLeast(1)
          .coerceAtMost(requested.coerceIn(1, 6))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      children.withIndex().chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          row.forEach { (index, child) ->
            key(index) { Box(Modifier.weight(1f)) { CardCluster(child, renderLeaf) } }
          }
          repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
      }
    }
  }
}
