package ee.schimke.ha.rc.components

/**
 * Block-level markdown element. Inline syntax (links, images, emphasis,
 * code spans) has already been stripped to plain text.
 */
data class MarkdownBlock(
    val kind: Kind,
    val text: String,
    val level: Int = 0,
) {
    enum class Kind { Heading, Paragraph, Bullet, Divider }
}

/**
 * Tiny line-oriented Markdown parser. Recognises headings, unordered
 * bullets and horizontal rules; everything else becomes a paragraph.
 * Inline emphasis, links, images and code spans collapse to plain text
 * — the renderer applies one style per block, which is the most
 * RemoteText can express today.
 */
object Markdown {
    fun parse(source: String): List<MarkdownBlock> {
        val out = mutableListOf<MarkdownBlock>()
        var inFence = false
        for (raw in source.split('\n')) {
            val line = raw.trim()
            if (FENCE.matches(line)) {
                inFence = !inFence
                continue
            }
            if (inFence) {
                if (line.isNotEmpty()) out += MarkdownBlock(MarkdownBlock.Kind.Paragraph, line)
                continue
            }
            if (line.isEmpty()) continue
            if (DIVIDER.matches(line)) {
                out += MarkdownBlock(MarkdownBlock.Kind.Divider, "")
                continue
            }
            HEADING.matchEntire(line)?.let { m ->
                val level = m.groupValues[1].length.coerceAtMost(6)
                val text = stripInline(m.groupValues[2])
                if (text.isNotEmpty()) {
                    out += MarkdownBlock(MarkdownBlock.Kind.Heading, text, level)
                }
                return@let
            } ?: BULLET.matchEntire(line)?.let { m ->
                val text = stripInline(m.groupValues[1])
                if (text.isNotEmpty()) {
                    out += MarkdownBlock(MarkdownBlock.Kind.Bullet, text)
                }
            } ?: run {
                val text = stripInline(line)
                if (text.isNotEmpty()) {
                    out += MarkdownBlock(MarkdownBlock.Kind.Paragraph, text)
                }
            }
        }
        return out
    }

    private val HEADING = Regex("""^(#{1,6})\s*(.*?)\s*#*\s*$""")
    private val BULLET = Regex("""^[-*+]\s+(.*)$""")
    private val DIVIDER = Regex("""^(?:-{3,}|\*{3,}|_{3,})$""")
    private val FENCE = Regex("""^(?:`{3,}|~{3,}).*$""")

    private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val BOLD_STAR = Regex("""\*\*([^*]+)\*\*""")
    private val BOLD_UNDER = Regex("""__([^_]+)__""")
    private val STRIKE = Regex("""~~([^~]+)~~""")
    private val ITALIC_STAR = Regex("""\*([^*\s][^*]*?)\*""")
    private val ITALIC_UNDER = Regex("""(?<![A-Za-z0-9_])_([^_\s][^_]*?)_(?![A-Za-z0-9_])""")
    private val CODE = Regex("""`([^`]+)`""")

    /** Strip inline markdown markers, leaving the visible text. */
    fun stripInline(input: String): String {
        var s = input
        s = IMAGE.replace(s, "")
        s = LINK.replace(s) { it.groupValues[1] }
        s = CODE.replace(s) { it.groupValues[1] }
        s = STRIKE.replace(s) { it.groupValues[1] }
        s = BOLD_STAR.replace(s) { it.groupValues[1] }
        s = BOLD_UNDER.replace(s) { it.groupValues[1] }
        s = ITALIC_STAR.replace(s) { it.groupValues[1] }
        s = ITALIC_UNDER.replace(s) { it.groupValues[1] }
        return s.trim()
    }
}
