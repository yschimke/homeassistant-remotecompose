@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteFlowRow
import androidx.compose.remote.creation.compose.layout.RemoteImage
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.contentDescription
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.semantics
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.RemoteTextUnit
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.text.RemoteFontFamily
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * HA `markdown` card — renders parsed markdown blocks inside the card chrome. Headings, bullets and
 * paragraphs each pick a block-level style. A block drawn in one style (plain, or bold / code
 * throughout) emits a single [RemoteText]; blocks that carry links, images or mixed emphasis
 * ([MarkdownBlock.hasInlineMarkup]) lay their inline runs out as a flow row so link labels (accent
 * colour, clickable), emphasised words and badge images sit inline and wrap — falling back to a
 * non-wrapping row on capture profiles without the experimental FlowLayout op. Emphasis changes the
 * weight or family only (italic is parsed but drawn upright, see [MarkdownStyle.drawn]); the size
 * is always the block's [HaType] token.
 */
@Composable
@RemoteComposable
fun RemoteHaMarkdown(data: HaMarkdownData, modifier: RemoteModifier = RemoteModifier) {
  // The note's material is its visual family's (docs/design/everyday-cards.md §4): what it sits
  // on, how its title is set, and how its blocks are marked. Blocks keep their live bindings.
  val material = markdownMaterial()
  RemoteColumn(modifier = modifier.fillMaxWidth()) {
    RemoteBox(modifier = RemoteModifier.fillMaxWidth().then(material.surface)) {
      RemoteColumn(
        modifier = RemoteModifier.fillMaxWidth().then(material.decoration).then(material.padding)
      ) {
        if (data.title != null) {
          MarkdownTitle(cardTitle(data.title), adaptiveTitleSize(data.title), material.ink)
          RemoteBox(modifier = RemoteModifier.padding(top = 4.rdp))
        }
        val screen = material.screen
        if (screen != null) {
          RemoteColumn(modifier = screen) { MarkdownBlocks(data, material) }
        } else {
          MarkdownBlocks(data, material)
        }
      }
    }
    if (visualFamily() == HaVisualFamily.Bubble && LocalCardChromeEnabled.current) BubbleTail()
  }
}

/** The note's blocks, dressed by [material]. */
@Composable
@RemoteComposable
private fun MarkdownBlocks(data: HaMarkdownData, material: MarkdownMaterial) {
  val theme = haTheme()
  var step = 0
  data.blocks.forEachIndexed { index, block ->
    if (material.dividesBlocks && index > 0 && block.kind != MarkdownBlock.Kind.Divider) {
      RemoteBox(
        modifier =
          RemoteModifier.padding(vertical = 6.rdp)
            .fillMaxWidth()
            .height(1.rdp)
            .background(theme.divider)
      )
    }
    when (block.kind) {
      MarkdownBlock.Kind.Heading -> {
        val size =
          when (block.level) {
            1 -> HaType.Title
            2,
            3 -> HaType.BodyLarge
            else -> HaType.Body
          }
        val marker =
          if (material.showsMarkers) "#".repeat(block.level.coerceIn(1, 6)) + " " else null
        val line: @Composable () -> Unit = {
          MarkdownLine(
            block = block,
            theme = theme,
            fontSize = size,
            fontWeight = FontWeight.SemiBold,
            material = material,
            marker = marker,
          )
        }
        if (material.numbersHeadings) {
          step += 1
          val number = step
          RemoteRow(
            modifier = RemoteModifier.padding(vertical = 2.rdp),
            verticalAlignment = RemoteAlignment.CenterVertically,
            horizontalArrangement = RemoteArrangement.spacedBy(8.rdp),
          ) {
            LeafletStep(number)
            line()
          }
        } else {
          line()
        }
      }
      MarkdownBlock.Kind.Bullet ->
        MarkdownLine(
          block = block,
          theme = theme,
          fontSize = HaType.Body,
          fontWeight = material.bodyWeight,
          material = material,
          marker = if (material.showsMarkers) "- " else "• ",
        )
      MarkdownBlock.Kind.Divider -> {
        RemoteBox(
          modifier =
            RemoteModifier.padding(vertical = 4.rdp)
              .fillMaxWidth()
              .height(1.rdp)
              .background(theme.divider)
        )
      }
      MarkdownBlock.Kind.Paragraph ->
        MarkdownLine(
          block = block,
          theme = theme,
          fontSize = HaType.Body,
          fontWeight = material.bodyWeight,
          material = material,
        )
    }
  }
}

/**
 * Identity tier for the `markdown` family — the title plus the first line of body. The smallest
 * cell that still says "this is the X note and it starts with…"; drops the rest of the body (P5)
 * but keeps the P1 identity (title + first line). Used by the Fixed-mode converter at narrow
 * launcher / Wear cells; see docs/architecture/adaptive-card-layouts.md §"Bulk / time-series".
 */
@Composable
@RemoteComposable
fun RemoteHaMarkdownIdentity(data: HaMarkdownData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  val firstLine = data.blocks.firstOrNull { it.kind != MarkdownBlock.Kind.Divider }
  // The same material as the full note, so a launcher cell reads as the same family's note.
  val material = markdownMaterial()
  val haReference =
    visualFamily().let { it == HaVisualFamily.Classic || it == HaVisualFamily.HomeAssistant }
  RemoteBox(
    modifier = modifier.fillMaxSize().then(material.surface),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteColumn(
      modifier = RemoteModifier.fillMaxSize().then(material.decoration).then(material.padding),
      verticalArrangement = RemoteArrangement.spacedBy(4.rdp, RemoteAlignment.CenterVertically),
    ) {
      if (data.title != null) {
        if (haReference)
          RemoteText(
            text = cardTitle(data.title),
            color = theme.primaryText,
            fontSize = HaType.Title,
            fontWeight = FontWeight.SemiBold,
            style = haTextStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        else MarkdownTitle(cardTitle(data.title), HaType.Title, material.ink)
      }
      if (firstLine != null) {
        val emphasis = firstLine.drawnStyle
        RemoteText(
          text = firstLine.boundText ?: firstLine.text.rs,
          color = if (currentHaUberTheme().ink != null) material.ink else theme.secondaryText,
          fontSize = HaType.Body,
          fontWeight = emphasis.weight(FontWeight.Normal),
          fontFamily = emphasis.fontFamily,
          style = material.bodyStyle,
          maxLines = if (data.title != null) 2 else 3,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/**
 * One heading / bullet / paragraph line. A block in one emphasis renders as a single [RemoteText]
 * (preserving the live `boundText` binding); blocks with links, images or mixed emphasis render
 * their inline runs in a flow row.
 */
@Composable
@RemoteComposable
private fun MarkdownLine(
  block: MarkdownBlock,
  theme: RemoteHaTheme,
  fontSize: RemoteTextUnit,
  fontWeight: FontWeight,
  material: MarkdownMaterial,
  marker: String? = null,
) {
  val primary = material.ink
  val style = material.bodyStyle
  // Obsidian keeps the source markers, muted, in their own run; elsewhere a bullet's dot leads
  // the line in the body ink.
  val mutedMarker: @Composable () -> Unit = {
    if (marker != null) {
      RemoteText(
        text = marker.rs,
        color = theme.secondaryText,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontFamily = RemoteFontFamily.Monospace,
        style = style,
      )
    }
  }
  if (!block.hasInlineMarkup) {
    val text = block.boundText ?: block.text.rs
    val emphasis = block.drawnStyle
    val line: @Composable () -> Unit = {
      RemoteText(
        text = if (marker != null && !material.showsMarkers) marker.rs + text else text,
        color = primary,
        fontSize = fontSize,
        fontWeight = emphasis.weight(fontWeight),
        fontFamily = emphasis.fontFamily,
        style = style,
      )
    }
    if (marker != null && material.showsMarkers) {
      RemoteRow {
        mutedMarker()
        line()
      }
    } else {
      line()
    }
    return
  }

  val content: @Composable () -> Unit = {
    if (marker != null) {
      if (material.showsMarkers) mutedMarker()
      else
        RemoteText(
          text = marker.rs,
          color = primary,
          fontSize = fontSize,
          fontWeight = fontWeight,
          style = style,
        )
    }
    flowItems(block.inlines).forEach { item ->
      when (item) {
        is FlowItem.Word ->
          if (item.pieces.size == 1) {
            val (text, emphasis) = item.pieces.single()
            EmphasisText(text, emphasis, primary, fontSize, fontWeight, style)
          } else {
            // A word that changes emphasis part-way (`**bold**,`) stays one
            // unbreakable flow item, its pieces abutting with no gap.
            RemoteRow {
              item.pieces.forEach { (text, emphasis) ->
                EmphasisText(text, emphasis, primary, fontSize, fontWeight, style)
              }
            }
          }
        is FlowItem.Span -> InlineSpan(item.inline, theme, primary, fontSize, fontWeight, style)
      }
    }
  }

  // Wrap inline runs so a long badge row / link list flows onto more
  // lines. Surfaces whose capture writer rejects FlowLayout (Glance
  // Wear) degrade to a non-wrapping row.
  if (LocalSupportsFlowLayout.current) {
    RemoteFlowRow(
      modifier = RemoteModifier.fillMaxWidth(),
      horizontalArrangement = RemoteArrangement.spacedBy(4.rdp),
      verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
    ) {
      content()
    }
  } else {
    RemoteRow(horizontalArrangement = RemoteArrangement.spacedBy(4.rdp)) { content() }
  }
}

/**
 * One flow-row item: a whitespace-delimited [Word] of constant text, possibly spanning several
 * emphasis runs, or a [Span] — a link, an image or a live-bound text run — that keeps its own node.
 */
private sealed interface FlowItem {
  data class Word(val pieces: List<Pair<String, MarkdownStyle>>) : FlowItem

  data class Span(val inline: MarkdownInline) : FlowItem
}

/**
 * Splits constant text runs into words so the flow row can wrap between them; the row's horizontal
 * spacing stands in for the collapsed inter-word whitespace. Words are cut at whitespace only, so
 * `_word_.` stays one item whose italic and plain pieces abut.
 */
private fun flowItems(inlines: List<MarkdownInline>): List<FlowItem> {
  val items = mutableListOf<FlowItem>()
  val pieces = mutableListOf<Pair<String, MarkdownStyle>>()
  fun endWord() {
    if (pieces.isNotEmpty()) {
      items += FlowItem.Word(pieces.toList())
      pieces.clear()
    }
  }
  for (inline in inlines) {
    if (inline !is MarkdownInline.Text || inline.boundText != null) {
      endWord()
      items += FlowItem.Span(inline)
      continue
    }
    var start = 0
    for (i in 0..inline.text.length) {
      if (i == inline.text.length || inline.text[i].isWhitespace()) {
        if (i > start) pieces += inline.text.substring(start, i) to inline.style.drawn
        if (i < inline.text.length) endWord()
        start = i + 1
      }
    }
  }
  endWord()
  return items
}

/** A constant run of text in the block's size and weight, with [emphasis] applied. */
@Composable
@RemoteComposable
private fun EmphasisText(
  text: String,
  emphasis: MarkdownStyle,
  color: RemoteColor,
  fontSize: RemoteTextUnit,
  fontWeight: FontWeight,
  style: RemoteTextStyle,
) {
  RemoteText(
    text = text.rs,
    color = color,
    fontSize = fontSize,
    fontWeight = emphasis.weight(fontWeight),
    fontFamily = emphasis.fontFamily,
    style = style,
  )
}

/** A link, an image, or a live-bound text run inside a flow row. */
@Composable
@RemoteComposable
private fun InlineSpan(
  inline: MarkdownInline,
  theme: RemoteHaTheme,
  primary: RemoteColor,
  fontSize: RemoteTextUnit,
  fontWeight: FontWeight,
  style: RemoteTextStyle,
) {
  val link = theme.linkText
  when (inline) {
    is MarkdownInline.Text -> {
      // A live `{{ states('x') }}` run — render the bound string as
      // one node so it keeps updating (can't be word-split like a
      // constant run).
      val emphasis = inline.style.drawn
      RemoteText(
        text = inline.boundText ?: inline.text.rs,
        color = primary,
        fontSize = fontSize,
        fontWeight = emphasis.weight(fontWeight),
        fontFamily = emphasis.fontFamily,
        style = style,
      )
    }
    is MarkdownInline.Link -> {
      val click = HaAction.Url(inline.url).toRemoteAction()
      val emphasis = inline.style.drawn
      RemoteText(
        text = inline.boundText ?: inline.text.rs,
        color = link,
        fontSize = fontSize,
        fontWeight = emphasis.weight(fontWeight),
        fontFamily = emphasis.fontFamily,
        style = style,
        modifier = click?.let { RemoteModifier.clickable(it) } ?: RemoteModifier,
      )
    }
    is MarkdownInline.Image -> MarkdownBadge(inline)
  }
}

/** Bold emphasis lifts the block's weight to [FontWeight.Bold]; anything else keeps it. */
private fun MarkdownStyle.weight(base: FontWeight): FontWeight =
  if (bold && base < FontWeight.Bold) FontWeight.Bold else base

// No FontStyle is set for italic runs: rc-player-compose 2.12.0 decodes the text font-style field
// as Typeface bits (1 = bold) while RemoteCompose writes 1 = italic, so FontStyle.Italic would draw
// bold. Italic is held back (see MarkdownStyle.drawn) until the player decodes it as italic.

/** Inline code is set in the monospace family; other runs keep the theme's [haTextStyle] family. */
private val MarkdownStyle.fontFamily: RemoteFontFamily?
  get() = if (code) RemoteFontFamily.Monospace else null

/**
 * Inline badge / image. The bytes are fetched by URL at playback via the host's `BitmapLoader`, so
 * an embedding surface using markdown images must wire one (e.g. `CoilBitmapLoader`); offline
 * previews fall back to the host's placeholder bitmap. A `[![…](src)](href)` badge links to
 * [MarkdownInline.Image.href] when present.
 *
 * Uses [rememberLocalNamedRemoteBitmap]'s sized URL form rather than [RemoteHaImageUrl] — the
 * upstream URL bitmap defaults to a 1×1 decode slot, which the player rejects once the fetched
 * badge is larger ("dimensions don't match"). [BADGE_DECODE_MAX_PX] is the decode bound, not the
 * draw size; the modifier fixes the on-screen box.
 */
@Composable
@RemoteComposable
private fun MarkdownBadge(image: MarkdownInline.Image) {
  val click = image.href?.let { HaAction.Url(it).toRemoteAction() }
  val rb =
    rememberLocalNamedRemoteBitmap(
      name = image.url,
      url = image.url,
      width = BADGE_DECODE_MAX_W_PX,
      height = BADGE_DECODE_MAX_H_PX,
    )
  val label = image.alt.ifEmpty { image.href ?: "image" }.rs
  // [description] is null inside the labelled tap target, so the badge is announced once.
  val badge: @Composable @RemoteComposable (description: RemoteString?) -> Unit = { description ->
    RemoteImage(
      remoteBitmap = rb,
      contentDescription = description,
      modifier = RemoteModifier.height(BADGE_HEIGHT_DP.rdp).width(BADGE_WIDTH_DP.rdp),
      contentScale = ContentScale.Fit,
    )
  }
  if (click == null) {
    badge(label)
  } else {
    // A linked badge is a button: the tap target carries the badge's label (the image's own
    // description doesn't reach the clickable node) and is a full touch target tall, with the badge
    // centred in it.
    RemoteBox(
      modifier =
        RemoteModifier.clickable(click)
          .semantics { contentDescription = label }
          .height(MIN_TOUCH_TARGET_DP.rdp),
      contentAlignment = RemoteAlignment.Center,
    ) {
      badge(null)
    }
  }
}

/** The smallest tap target, in dp (Material's and the accessibility checker's 48dp). */
private const val MIN_TOUCH_TARGET_DP = 48

// Badge SVGs are ~20 px tall and a few times wider; a fixed box keeps the
// flow row tidy and lets several badges share a line. Fit letterboxes any
// odd aspect rather than distorting it.
private const val BADGE_HEIGHT_DP = 18
private const val BADGE_WIDTH_DP = 96

// Decode-bound (max) for URL badge fetches. Badges are wide-and-short, so
// the bound matches that aspect (a square bound would reject a normal
// badge whose width exceeds it). The fetched bitmap must fit within both
// dimensions; the on-screen size is set by the modifier above. Kept small
// so a card full of badges stays well under the player's per-document
// bitmap-memory budget (384×128×4 ≈ 196 KB each).
private const val BADGE_DECODE_MAX_W_PX = 384
private const val BADGE_DECODE_MAX_H_PX = 128
