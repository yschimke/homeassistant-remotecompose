package ee.schimke.ha.rc.components

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * Block-level markdown element. Inline syntax (links, images, emphasis,
 * code spans) has already been collapsed to plain text — RemoteText
 * applies one style per node.
 */
data class MarkdownBlock(
    val kind: Kind,
    val text: String,
    val boundText: androidx.compose.remote.creation.compose.state.RemoteString? = null,
    val level: Int = 0,
) {
    enum class Kind { Heading, Paragraph, Bullet, Divider }
}

/**
 * Markdown → block list using JetBrains' multiplatform parser. The
 * parser is responsible for block boundaries (CommonMark + GFM
 * extensions); we walk the AST and emit one [MarkdownBlock] per
 * top-level block, dropping inline markup so each block can be
 * rendered with a single text style.
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
            MarkdownElementTypes.ATX_1 -> heading(node, src, 1, out)
            MarkdownElementTypes.ATX_2 -> heading(node, src, 2, out)
            MarkdownElementTypes.ATX_3 -> heading(node, src, 3, out)
            MarkdownElementTypes.ATX_4 -> heading(node, src, 4, out)
            MarkdownElementTypes.ATX_5 -> heading(node, src, 5, out)
            MarkdownElementTypes.ATX_6 -> heading(node, src, 6, out)
            MarkdownElementTypes.SETEXT_1 -> heading(node, src, 1, out)
            MarkdownElementTypes.SETEXT_2 -> heading(node, src, 2, out)
            MarkdownElementTypes.PARAGRAPH -> {
                val text = visibleText(node, src)
                if (text.isNotEmpty()) out += MarkdownBlock(MarkdownBlock.Kind.Paragraph, text)
            }
            MarkdownElementTypes.UNORDERED_LIST,
            MarkdownElementTypes.ORDERED_LIST -> {
                for (item in node.children) {
                    if (item.type == MarkdownElementTypes.LIST_ITEM) {
                        val text = visibleText(item, src)
                        if (text.isNotEmpty()) out += MarkdownBlock(MarkdownBlock.Kind.Bullet, text)
                    }
                }
            }
            MarkdownTokenTypes.HORIZONTAL_RULE -> out += MarkdownBlock(MarkdownBlock.Kind.Divider, "")
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

    private fun heading(node: ASTNode, src: String, level: Int, out: MutableList<MarkdownBlock>) {
        val text = visibleText(node, src)
        if (text.isNotEmpty()) {
            out += MarkdownBlock(MarkdownBlock.Kind.Heading, text, level = level)
        }
    }

    /** Concatenate visible inline text, dropping images and link URLs. */
    private fun visibleText(node: ASTNode, src: String): String {
        val sb = StringBuilder()
        appendVisible(node, src, sb)
        return sb.toString().replace(WHITESPACE, " ").trim()
    }

    private fun appendVisible(node: ASTNode, src: String, sb: StringBuilder) {
        when (node.type) {
            MarkdownElementTypes.IMAGE -> return
            MarkdownElementTypes.AUTOLINK -> {
                sb.append(
                    node.getTextInNode(src).toString().trim().removePrefix("<").removeSuffix(">"),
                )
                return
            }
            MarkdownElementTypes.INLINE_LINK,
            MarkdownElementTypes.FULL_REFERENCE_LINK,
            MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
                node.children
                    .firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                    ?.let { linkText ->
                        for (c in linkText.children) {
                            if (c.type != MarkdownTokenTypes.LBRACKET &&
                                c.type != MarkdownTokenTypes.RBRACKET) {
                                appendVisible(c, src, sb)
                            }
                        }
                    }
                return
            }
        }
        if (node.children.isEmpty()) {
            when (node.type) {
                MarkdownTokenTypes.EOL,
                MarkdownTokenTypes.HARD_LINE_BREAK -> sb.append(' ')
                in MARKUP_TOKENS -> Unit
                else -> sb.append(node.getTextInNode(src))
            }
        } else {
            node.children.forEach { appendVisible(it, src, sb) }
        }
    }

    private val WHITESPACE = Regex("\\s+")

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
