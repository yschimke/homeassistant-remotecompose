package ee.schimke.ha.rc.components

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * One inline run inside a [MarkdownBlock]. The parser keeps links and
 * images as their own spans instead of flattening the whole block to
 * plain text, so [RemoteHaMarkdown] can lay a paragraph out as a flow
 * row of styled runs — link labels in the accent colour, badge images
 * fetched by URL — rather than dropping the URLs on the floor.
 */
sealed interface MarkdownInline {
    /** Visible text this span contributes to the block (empty for images). */
    val text: String

    /**
     * Plain run; rendered with the block's own text style. [boundText]
     * carries the live `RemoteString` when [text] holds a
     * `{{ states('x') }}` binding token, so the rich-inline path keeps
     * updating instead of drawing the literal token.
     */
    data class Text(
        override val text: String,
        val boundText: androidx.compose.remote.creation.compose.state.RemoteString? = null,
    ) : MarkdownInline

    /** `[label](url)` — a clickable, accent-coloured run. [boundText]
     *  carries the live label when it holds a binding token. */
    data class Link(
        override val text: String,
        val url: String,
        val boundText: androidx.compose.remote.creation.compose.state.RemoteString? = null,
    ) : MarkdownInline

    /**
     * `![alt](url)`, or the image inside a `[![alt](url)](href)` badge.
     * [url] is the image source the host's `BitmapLoader` resolves;
     * [href] is the optional link the surrounding `[…]` points at.
     * Images contribute no visible text.
     */
    data class Image(
        val url: String,
        val alt: String,
        val href: String? = null,
    ) : MarkdownInline {
        override val text: String get() = ""
    }
}

/**
 * Block-level markdown element. [inlines] carries the block's inline
 * runs (text / links / images); [text] is the concatenated visible text,
 * kept for height heuristics and the simple `{{ states('x') }}` binding
 * path. Plain blocks (no links or images) still render as a single
 * RemoteText; only [hasInlineMarkup] blocks take the flow-row path.
 */
data class MarkdownBlock(
    val kind: Kind,
    val text: String,
    val boundText: androidx.compose.remote.creation.compose.state.RemoteString? = null,
    val level: Int = 0,
    val inlines: List<MarkdownInline> = listOf(MarkdownInline.Text(text)),
) {
    enum class Kind { Heading, Paragraph, Bullet, Divider }

    /**
     * True when the block carries links or images — these need the
     * inline flow-row path in [RemoteHaMarkdown]. A plain block renders
     * as one [androidx.compose.remote.creation.compose.layout.RemoteText].
     */
    val hasInlineMarkup: Boolean
        get() = inlines.any { it is MarkdownInline.Link || it is MarkdownInline.Image }
}

/**
 * Markdown → block list using JetBrains' multiplatform parser. The
 * parser owns block boundaries (CommonMark + GFM extensions); we walk
 * the AST and emit one [MarkdownBlock] per top-level block. Inline
 * syntax is split into [MarkdownInline] runs so links and images
 * survive to the renderer; emphasis / code markup collapses to plain
 * text.
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
                node.getTextInNode(src).toString().lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("~~~") }
                    .forEach { out += MarkdownBlock(MarkdownBlock.Kind.Paragraph, it) }
            }
            MarkdownElementTypes.BLOCK_QUOTE -> node.children.forEach { visit(it, src, out) }
            else -> if (node.children.isNotEmpty()) {
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

    /** Concatenated visible text, whitespace-collapsed — used for height
     *  heuristics and the simple state-binding path. */
    private fun textOf(spans: List<MarkdownInline>): String =
        spans.joinToString("") { it.text }.replace(HORIZONTAL_WHITESPACE, " ").trim()

    // --- inline walk -------------------------------------------------------

    private fun inlineSpans(node: ASTNode, src: String): List<MarkdownInline> {
        val out = mutableListOf<MarkdownInline>()
        val sb = StringBuilder()
        appendInline(node, src, sb, out)
        flush(sb, out)
        return out
    }

    private fun flush(sb: StringBuilder, out: MutableList<MarkdownInline>) {
        if (sb.isNotEmpty()) {
            out += MarkdownInline.Text(sb.toString())
            sb.setLength(0)
        }
    }

    private fun appendInline(
        node: ASTNode,
        src: String,
        sb: StringBuilder,
        out: MutableList<MarkdownInline>,
    ) {
        when (node.type) {
            MarkdownElementTypes.IMAGE -> {
                flush(sb, out)
                out += imageSpan(node, src, href = null)
                return
            }
            MarkdownElementTypes.AUTOLINK -> {
                flush(sb, out)
                val url = node.getTextInNode(src).toString().trim()
                    .removePrefix("<").removeSuffix(">")
                out += MarkdownInline.Link(url, url)
                return
            }
            MarkdownElementTypes.INLINE_LINK,
            MarkdownElementTypes.FULL_REFERENCE_LINK,
            MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
                flush(sb, out)
                appendLink(node, src, out)
                return
            }
        }
        if (node.children.isEmpty()) {
            when (node.type) {
                MarkdownTokenTypes.EOL,
                MarkdownTokenTypes.HARD_LINE_BREAK -> sb.append('\n')
                in MARKUP_TOKENS -> Unit
                else -> sb.append(node.getTextInNode(src))
            }
        } else {
            node.children.forEach { appendInline(it, src, sb, out) }
        }
    }

    private fun appendLink(node: ASTNode, src: String, out: MutableList<MarkdownInline>) {
        // The link's own destination is a direct child (the image inside a
        // badge has its own, deeper, LINK_DESTINATION — don't grab that one).
        val dest = node.children
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
        val url = firstDescendant(node, MarkdownElementTypes.LINK_DESTINATION)
            ?.let { linkUrl(it, src) }.orEmpty()
        val alt = firstDescendant(node, MarkdownElementTypes.LINK_TEXT)
            ?.let { labelText(it, src) }.orEmpty()
        return MarkdownInline.Image(url = url, alt = alt, href = href)
    }

    private fun linkUrl(node: ASTNode, src: String): String =
        node.getTextInNode(src).toString().trim().removePrefix("<").removeSuffix(">")

    /** Visible text of a `[…]` label, dropping brackets, markup and any
     *  nested image. */
    private fun labelText(label: ASTNode, src: String): String {
        val sb = StringBuilder()
        for (child in label.children) {
            if (child.type == MarkdownTokenTypes.LBRACKET ||
                child.type == MarkdownTokenTypes.RBRACKET
            ) {
                continue
            }
            appendPlain(child, src, sb)
        }
        return sb.toString().replace(HORIZONTAL_WHITESPACE, " ").trim()
    }

    private fun appendPlain(node: ASTNode, src: String, sb: StringBuilder) {
        if (node.type == MarkdownElementTypes.IMAGE) return
        if (node.children.isEmpty()) {
            when (node.type) {
                MarkdownTokenTypes.EOL,
                MarkdownTokenTypes.HARD_LINE_BREAK -> sb.append(' ')
                in MARKUP_TOKENS -> Unit
                else -> sb.append(node.getTextInNode(src))
            }
        } else {
            node.children.forEach { appendPlain(it, src, sb) }
        }
    }

    private fun firstDescendant(node: ASTNode, type: org.intellij.markdown.IElementType): ASTNode? {
        if (node.type == type) return node
        for (child in node.children) {
            firstDescendant(child, type)?.let { return it }
        }
        return null
    }

    /** Collapse horizontal whitespace, merge adjacent text runs, drop the
     *  empties, and trim the block's leading / trailing whitespace. */
    private fun normalize(spans: List<MarkdownInline>): List<MarkdownInline> {
        val merged = mutableListOf<MarkdownInline>()
        for (span in spans) {
            if (span is MarkdownInline.Text) {
                val collapsed = span.text.replace(HORIZONTAL_WHITESPACE, " ")
                val last = merged.lastOrNull()
                if (last is MarkdownInline.Text) {
                    merged[merged.lastIndex] = MarkdownInline.Text(last.text + collapsed)
                } else {
                    merged += MarkdownInline.Text(collapsed)
                }
            } else {
                merged += span
            }
        }
        (merged.firstOrNull() as? MarkdownInline.Text)?.let {
            merged[0] = MarkdownInline.Text(it.text.trimStart())
        }
        (merged.lastOrNull() as? MarkdownInline.Text)?.let {
            merged[merged.lastIndex] = MarkdownInline.Text(it.text.trimEnd())
        }
        return merged.filterNot { it is MarkdownInline.Text && it.text.isEmpty() }
    }

    private val HORIZONTAL_WHITESPACE = Regex("[ \\t\\x0B\\f\\r]+")

    private val MARKUP_TOKENS = setOf(
        MarkdownTokenTypes.EMPH,
        MarkdownTokenTypes.BACKTICK,
        MarkdownTokenTypes.ESCAPED_BACKTICKS,
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
