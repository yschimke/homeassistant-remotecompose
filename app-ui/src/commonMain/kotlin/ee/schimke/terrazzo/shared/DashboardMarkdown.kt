package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
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
          Text(markdownInline(plain), style = style)
        }
  }
}

private val INLINE_LINK = Regex("""!?\[((?:!\[[^\]]*]\([^)]*\)|[^\]])*)]\(([^)\s]*)[^)]*\)""")
private val INLINE_IMAGE = Regex("""!\[([^\]]*)]\([^)]*\)""")

/**
 * One line of Markdown's inline syntax as text: `**bold**`, `[text](url)` (a link when the URL is
 * absolute HTTP(S); text otherwise, since a Home Assistant path can't open from here) and
 * `![alt](src)` (its alt text: badges and pictures aren't fetched). Never the raw source.
 */
internal fun markdownInline(line: String): AnnotatedString = buildAnnotatedString {
  var at = 0
  for (match in INLINE_LINK.findAll(line)) {
    appendBold(line.substring(at, match.range.first))
    val (label, target) = match.destructured
    val text = INLINE_IMAGE.replace(label) { it.groupValues[1] }.trim()
    if (match.value.startsWith("!")) appendBold(text)
    else if (target.startsWith("https://") || target.startsWith("http://"))
      withLink(
        LinkAnnotation.Url(
          target,
          TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)),
        )
      ) {
        appendBold(text)
      }
    else appendBold(text)
    at = match.range.last + 1
  }
  appendBold(line.substring(at))
}

private fun AnnotatedString.Builder.appendBold(text: String) {
  text.split("**").forEachIndexed { index, part ->
    if (index % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) }
    else append(part)
  }
}
