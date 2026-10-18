package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Render the shared Jinja subset locally; unsupported sources never appear as raw source code. */
@Composable
internal fun DashboardMarkdown(card: CardConfig, snapshot: HaSnapshot) {
  val source = (card.raw["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
  val rendered = JinjaTemplate.render(source, snapshot)
  Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (rendered == null)
      Text(
        "This template needs Home Assistant’s renderer.",
        style = MaterialTheme.typography.bodyMedium,
      )
    else
      rendered
        .lines()
        .filter { it.isNotBlank() }
        .forEach { line ->
          val style =
            when {
              line.startsWith("# ") -> MaterialTheme.typography.headlineSmall
              line.startsWith("## ") -> MaterialTheme.typography.titleLarge
              line.startsWith("### ") -> MaterialTheme.typography.titleMedium
              else -> MaterialTheme.typography.bodyMedium
            }
          val plain = line.replaceFirst(Regex("^#{1,6}\\s+"), "")
          Text(
            buildAnnotatedString {
              plain.split("**").forEachIndexed { index, part ->
                if (index % 2 == 1)
                  withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) }
                else append(part)
              }
            },
            style = style,
          )
        }
  }
}
