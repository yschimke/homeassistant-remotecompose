package ee.schimke.ha.rc.components

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * Inline emphasis on a [MarkdownInline.Text] run, as CommonMark (and so HA's markdown card, which
 * renders through marked) defines it: `*x*` / `_x_` italic, `**x**` / `__x__` bold, `***x***` both,
 * and `` `x` `` inline code. The flags combine, so nesting (`**bold _and italic_**`) keeps both.
 */
data class MarkdownStyle(
  val bold: Boolean = false,
  val italic: Boolean = false,
  val code: Boolean = false,
) {
  val isPlain: Boolean
    get() = !bold && !italic && !code

  /**
   * The part of this style [RemoteHaMarkdown] actually draws: bold and code. Italic is parsed but
   * held back — rc-player-compose 2.12.0 decodes the text font-style field as Typeface bits (1 =
   * bold) while RemoteCompose writes 1 = italic, so an italic run would show as bold. Italic text
   * draws upright until the player decodes it as italic.
   */
  val drawn: MarkdownStyle
    get() = if (italic) copy(italic = false) else this

  companion object {
    val Plain = MarkdownStyle()
    val Code = MarkdownStyle(code = true)
  }
}

/**
 * One inline run inside a [MarkdownBlock]. The parser keeps links and images as their own spans
 * instead of flattening the whole block to plain text, so [RemoteHaMarkdown] can lay a paragraph
 * out as a flow row of styled runs — link labels in the accent colour, badge images fetched by URL,
 * emphasised words in italic / bold / monospace — rather than dropping the markup on the floor.
 */
sealed interface MarkdownInline {
  /** Visible text this span contributes to the block (empty for images). */
  val text: String

  /**
   * Text run; rendered with the block's own text style plus its [style] emphasis. [boundText]
   * carries the live `RemoteString` when [text] holds a `{{ states('x') }}` binding token, so the
   * rich-inline path keeps updating instead of drawing the literal token.
   */
  data class Text(
    override val text: String,
    val boundText: androidx.compose.remote.creation.compose.state.RemoteString? = null,
    val style: MarkdownStyle = MarkdownStyle.Plain,
  ) : MarkdownInline

  /**
   * `[label](url)` — a clickable, accent-coloured run. [boundText] carries the live label when it
   * holds a binding token.
   */
  data class Link(
    override val text: String,
    val url: String,
    val boundText: androidx.compose.remote.creation.compose.state.RemoteString? = null,
  ) : MarkdownInline

  /**
   * `![alt](url)`, or the image inside a `[![alt](url)](href)` badge. [url] is the image source the
   * host's `BitmapLoader` resolves; [href] is the optional link the surrounding `[…]` points at.
   * Images contribute no visible text.
   */
  data class Image(val url: String, val alt: String, val href: String? = null) : MarkdownInline {
    override val text: String
      get() = ""
  }
}

/**
 * Block-level markdown element. [inlines] carries the block's inline runs (text / links / images);
 * [text] is the concatenated visible text, kept for height heuristics and the simple `{{
 * states('x') }}` binding path. Blocks without links, images or mixed emphasis still render as a
 * single RemoteText — in the block's uniform [style] — and only [hasInlineMarkup] blocks take the
 * flow-row path.
 */
data class MarkdownBlock(
  val kind: Kind,
  val text: String,
  val boundText: androidx.compose.remote.creation.compose.state.RemoteString? = null,
  val level: Int = 0,
  val inlines: List<MarkdownInline> = listOf(MarkdownInline.Text(text)),
) {
  enum class Kind {
    Heading,
    Paragraph,
    Bullet,
    Divider,
  }

  /**
   * True when the block carries links, images, or text runs drawn in more than one style (see
   * [MarkdownStyle.drawn]) — these need the inline flow-row path in [RemoteHaMarkdown]. Any other
   * block renders as one [androidx.compose.remote.creation.compose.layout.RemoteText] in
   * [drawnStyle].
   */
  val hasInlineMarkup: Boolean
    get() =
      inlines.any { it is MarkdownInline.Link || it is MarkdownInline.Image } ||
        drawnStyles.size > 1

  /**
   * The emphasis every text run of the block shares (`_Mobile companion for MeshCore_` is italic
   * throughout), or [MarkdownStyle.Plain] when the runs differ.
   */
  val style: MarkdownStyle
    get() = textStyles.singleOrNull() ?: MarkdownStyle.Plain

  /**
   * The drawn style every text run of the block shares — [style] without what the renderer holds
   * back — or [MarkdownStyle.Plain] when the runs draw differently.
   */
  val drawnStyle: MarkdownStyle
    get() = drawnStyles.singleOrNull() ?: MarkdownStyle.Plain

  private val textStyles: Set<MarkdownStyle>
    get() = inlines.filterIsInstance<MarkdownInline.Text>().mapTo(mutableSetOf()) { it.style }

  private val drawnStyles: Set<MarkdownStyle>
    get() = textStyles.mapTo(mutableSetOf()) { it.drawn }
}

/**
 * Markdown → block list using JetBrains' multiplatform parser. The parser owns block boundaries
 * (CommonMark + GFM extensions); we walk the AST and emit one [MarkdownBlock] per top-level block.
 * Inline syntax is split into [MarkdownInline] runs so links and images survive to the renderer,
 * and emphasis / inline code become [MarkdownStyle]d text runs. Delimiters the parser does not pair
 * (an unmatched `*`, an intraword `_`, an escaped `\*`) stay literal text, as CommonMark has it.
 * Strikethrough markup still collapses to plain text.
 */
object Markdown {
  private val flavour = GFMFlavourDescriptor()

  fun parse(source: String): List<MarkdownBlock> {
    if (source.isBlank()) return emptyList()
    val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(source)
    val out = mutableListOf<MarkdownBlock>()
    visit(tree, source, out)
    return out
  }

  private fun visit(node: ASTNode, src: String, out: MutableList<MarkdownBlock>) {
    when (node.type) {
      MarkdownElementTypes.ATX_1 -> emit(MarkdownBlock.Kind.Heading, node, src, 1, out)
      MarkdownElementTypes.ATX_2 -> emit(MarkdownBlock.Kind.Heading, node, src, 2, out)
      MarkdownElementTypes.ATX_3 -> emit(MarkdownBlock.Kind.Heading, node, src, 3, out)
      MarkdownElementTypes.ATX_4 -> emit(MarkdownBlock.Kind.Heading, node, src, 4, out)
      MarkdownElementTypes.ATX_5 -> emit(MarkdownBlock.Kind.Heading, node, src, 5, out)
      MarkdownElementTypes.ATX_6 -> emit(MarkdownBlock.Kind.Heading, node, src, 6, out)
      MarkdownElementTypes.SETEXT_1 -> emit(MarkdownBlock.Kind.Heading, node, src, 1, out)
      MarkdownElementTypes.SETEXT_2 -> emit(MarkdownBlock.Kind.Heading, node, src, 2, out)
      MarkdownElementTypes.PARAGRAPH -> emit(MarkdownBlock.Kind.Paragraph, node, src, 0, out)
      MarkdownElementTypes.UNORDERED_LIST,
      MarkdownElementTypes.ORDERED_LIST -> {
        for (item in node.children) {
          if (item.type == MarkdownElementTypes.LIST_ITEM) {
            emit(MarkdownBlock.Kind.Bullet, item, src, 0, out)
          }
        }
      }
      MarkdownTokenTypes.HORIZONTAL_RULE ->
        out += MarkdownBlock(MarkdownBlock.Kind.Divider, "", inlines = emptyList())
      MarkdownElementTypes.CODE_FENCE,
      MarkdownElementTypes.CODE_BLOCK -> {
        node
          .getTextInNode(src)
          .toString()
          .lineSequence()
          .map { it.trim() }
          .filter { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("~~~") }
          .forEach {
            out +=
              MarkdownBlock(
                MarkdownBlock.Kind.Paragraph,
                it,
                inlines = listOf(MarkdownInline.Text(it, style = MarkdownStyle.Code)),
              )
          }
      }
      MarkdownElementTypes.BLOCK_QUOTE -> node.children.forEach { visit(it, src, out) }
      else ->
        if (node.children.isNotEmpty()) {
          node.children.forEach { visit(it, src, out) }
        }
    }
  }

  private fun emit(
    kind: MarkdownBlock.Kind,
    node: ASTNode,
    src: String,
    level: Int,
    out: MutableList<MarkdownBlock>,
  ) {
    val inlines = normalize(inlineSpans(node, src))
    if (inlines.isEmpty()) return
    out += MarkdownBlock(kind, textOf(inlines), level = level, inlines = inlines)
  }

  /**
   * Concatenated visible text, whitespace-collapsed — used for height heuristics and the simple
   * state-binding path.
   */
  private fun textOf(spans: List<MarkdownInline>): String =
    spans.joinToString("") { it.text }.replace(HORIZONTAL_WHITESPACE, " ").trim()

  // --- inline walk -------------------------------------------------------

  private fun inlineSpans(node: ASTNode, src: String): List<MarkdownInline> {
    val runs = Runs()
    appendInline(node, src, MarkdownStyle.Plain, runs)
    runs.flush()
    return runs.out
  }

  /** Accumulates text into one run per contiguous [MarkdownStyle]. */
  private class Runs {
    val out = mutableListOf<MarkdownInline>()
    private val sb = StringBuilder()
    private var style = MarkdownStyle.Plain

    fun text(value: CharSequence, style: MarkdownStyle) {
      if (style != this.style) {
        flush()
        this.style = style
      }
      sb.append(value)
    }

    fun span(span: MarkdownInline) {
      flush()
      out += span
    }

    fun flush() {
      if (sb.isNotEmpty()) {
        out += MarkdownInline.Text(sb.toString(), style = style)
        sb.setLength(0)
      }
    }
  }

  private fun appendInline(node: ASTNode, src: String, style: MarkdownStyle, runs: Runs) {
    when (node.type) {
      MarkdownElementTypes.IMAGE -> {
        runs.span(imageSpan(node, src, href = null))
        return
      }
      MarkdownElementTypes.AUTOLINK -> {
        val url = node.getTextInNode(src).toString().trim().removePrefix("<").removeSuffix(">")
        runs.span(MarkdownInline.Link(url, url))
        return
      }
      MarkdownElementTypes.INLINE_LINK,
      MarkdownElementTypes.FULL_REFERENCE_LINK,
      MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
        runs.flush()
        appendLink(node, src, runs.out)
        return
      }
    }
    if (node.children.isEmpty()) {
      when {
        node.type == MarkdownTokenTypes.EOL || node.type == MarkdownTokenTypes.HARD_LINE_BREAK ->
          runs.text("\n", MarkdownStyle.Plain)
        isMarkup(node) -> Unit
        else -> runs.text(leafText(node, src), style)
      }
    } else {
      val inner =
        when (node.type) {
          MarkdownElementTypes.EMPH -> style.copy(italic = true)
          MarkdownElementTypes.STRONG -> style.copy(bold = true)
          MarkdownElementTypes.CODE_SPAN -> style.copy(code = true)
          else -> style
        }
      node.children.forEach { appendInline(it, src, inner, runs) }
    }
  }

  /**
   * True for a delimiter token the parser paired into markup — the `*` / `_` of an emphasis or
   * strong element, a code span's backticks, a strikethrough's tildes, link brackets, heading and
   * list markers. An emphasis delimiter or backtick the parser left unpaired is literal text.
   */
  private fun isMarkup(node: ASTNode): Boolean {
    val parent = node.parent?.type
    return when (node.type) {
      MarkdownTokenTypes.EMPH ->
        parent == MarkdownElementTypes.EMPH || parent == MarkdownElementTypes.STRONG
      MarkdownTokenTypes.BACKTICK,
      MarkdownTokenTypes.ESCAPED_BACKTICKS -> parent == MarkdownElementTypes.CODE_SPAN
      else -> node.type in MARKUP_TOKENS
    }
  }

  /**
   * A leaf's visible text. A backslash escape (`\*`, `\_`, `\#`) drops its backslash outside code
   * spans, where CommonMark keeps backslashes literal.
   */
  private fun leafText(node: ASTNode, src: String): CharSequence {
    val text = node.getTextInNode(src)
    if (text.indexOf('\\') < 0 || insideCodeSpan(node)) return text
    return BACKSLASH_ESCAPE.replace(text, "$1")
  }

  private fun insideCodeSpan(node: ASTNode): Boolean {
    var p = node.parent
    while (p != null) {
      if (p.type == MarkdownElementTypes.CODE_SPAN) return true
      p = p.parent
    }
    return false
  }

  private fun appendLink(node: ASTNode, src: String, out: MutableList<MarkdownInline>) {
    // The link's own destination is a direct child (the image inside a
    // badge has its own, deeper, LINK_DESTINATION — don't grab that one).
    val dest =
      node.children
        .firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
        ?.let { linkUrl(it, src) }
    val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
    val image = label?.let { firstDescendant(it, MarkdownElementTypes.IMAGE) }
    if (image != null) {
      // `[![alt](src)](href)` — render the badge image, link it to href.
      out += imageSpan(image, src, href = dest)
      return
    }
    val text = label?.let { labelText(it, src) }.orEmpty()
    if (text.isEmpty()) return
    out += if (dest != null) MarkdownInline.Link(text, dest) else MarkdownInline.Text(text)
  }

  private fun imageSpan(node: ASTNode, src: String, href: String?): MarkdownInline.Image {
    val url =
      firstDescendant(node, MarkdownElementTypes.LINK_DESTINATION)
        ?.let { linkUrl(it, src) }
        .orEmpty()
    val alt =
      firstDescendant(node, MarkdownElementTypes.LINK_TEXT)?.let { labelText(it, src) }.orEmpty()
    return MarkdownInline.Image(url = url, alt = alt, href = href)
  }

  private fun linkUrl(node: ASTNode, src: String): String =
    node.getTextInNode(src).toString().trim().removePrefix("<").removeSuffix(">")

  /** Visible text of a `[…]` label, dropping brackets, markup and any nested image. */
  private fun labelText(label: ASTNode, src: String): String {
    val sb = StringBuilder()
    for (child in label.children) {
      if (child.type == MarkdownTokenTypes.LBRACKET || child.type == MarkdownTokenTypes.RBRACKET) {
        continue
      }
      appendPlain(child, src, sb)
    }
    return sb.toString().replace(HORIZONTAL_WHITESPACE, " ").trim()
  }

  private fun appendPlain(node: ASTNode, src: String, sb: StringBuilder) {
    if (node.type == MarkdownElementTypes.IMAGE) return
    if (node.children.isEmpty()) {
      when {
        node.type == MarkdownTokenTypes.EOL || node.type == MarkdownTokenTypes.HARD_LINE_BREAK ->
          sb.append(' ')
        isMarkup(node) -> Unit
        else -> sb.append(leafText(node, src))
      }
    } else {
      node.children.forEach { appendPlain(it, src, sb) }
    }
  }

  private fun firstDescendant(node: ASTNode, type: org.intellij.markdown.IElementType): ASTNode? {
    if (node.type == type) return node
    for (child in node.children) {
      firstDescendant(child, type)?.let {
        return it
      }
    }
    return null
  }

  /**
   * Collapse horizontal whitespace, merge adjacent text runs of one style, drop the empties, and
   * trim the block's leading / trailing whitespace. A whitespace-only run carries no visible style,
   * so it joins the run before it: `*a* *b*` is one italic run.
   */
  private fun normalize(spans: List<MarkdownInline>): List<MarkdownInline> {
    val merged = mutableListOf<MarkdownInline>()
    for (span in spans) {
      if (span is MarkdownInline.Text) {
        val collapsed = span.text.replace(HORIZONTAL_WHITESPACE, " ")
        val last = merged.lastOrNull()
        if (last is MarkdownInline.Text && (last.style == span.style || collapsed.isBlank())) {
          merged[merged.lastIndex] = last.copy(text = last.text + collapsed)
        } else if (last is MarkdownInline.Text && last.text.isBlank()) {
          merged[merged.lastIndex] = span.copy(text = last.text + collapsed)
        } else {
          merged += span.copy(text = collapsed)
        }
      } else {
        merged += span
      }
    }
    (merged.firstOrNull() as? MarkdownInline.Text)?.let {
      merged[0] = it.copy(text = it.text.trimStart())
    }
    (merged.lastOrNull() as? MarkdownInline.Text)?.let {
      merged[merged.lastIndex] = it.copy(text = it.text.trimEnd())
    }
    return merged.filterNot { it is MarkdownInline.Text && it.text.isEmpty() }
  }

  private val HORIZONTAL_WHITESPACE = Regex("[ \\t\\x0B\\f\\r]+")

  /** A CommonMark backslash escape: `\` before ASCII punctuation. */
  private val BACKSLASH_ESCAPE = Regex("""\\([!-/:-@\[-`{-~])""")

  private val MARKUP_TOKENS =
    setOf(
      MarkdownTokenTypes.LBRACKET,
      MarkdownTokenTypes.RBRACKET,
      MarkdownTokenTypes.LPAREN,
      MarkdownTokenTypes.RPAREN,
      MarkdownTokenTypes.LT,
      MarkdownTokenTypes.GT,
      MarkdownTokenTypes.EXCLAMATION_MARK,
      MarkdownTokenTypes.ATX_HEADER,
      MarkdownTokenTypes.SETEXT_1,
      MarkdownTokenTypes.SETEXT_2,
      MarkdownTokenTypes.LIST_BULLET,
      MarkdownTokenTypes.LIST_NUMBER,
      MarkdownTokenTypes.BLOCK_QUOTE,
      GFMTokenTypes.TILDE,
    )
}
