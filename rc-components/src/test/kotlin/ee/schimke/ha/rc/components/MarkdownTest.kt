package ee.schimke.ha.rc.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownTest {
    @Test
    fun headingsByLevel() {
        val blocks = Markdown.parse("# H1\n\n## H2\n\n### H3\n\n#### H4")
        assertEquals(4, blocks.size)
        assertEquals(MarkdownBlock.Kind.Heading, blocks[0].kind)
        assertEquals(1, blocks[0].level)
        assertEquals("H1", blocks[0].text)
        assertEquals(2, blocks[1].level)
        assertEquals(3, blocks[2].level)
        assertEquals(4, blocks[3].level)
    }

    @Test
    fun setextHeadings() {
        val blocks = Markdown.parse("Big heading\n===\n\nMedium\n---")
        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlock.Kind.Heading, blocks[0].kind)
        assertEquals(1, blocks[0].level)
        assertEquals("Big heading", blocks[0].text)
        assertEquals(2, blocks[1].level)
        assertEquals("Medium", blocks[1].text)
    }

    @Test
    fun bulletList() {
        val blocks = Markdown.parse("- one\n- two\n- three")
        assertEquals(3, blocks.size)
        assertTrue(blocks.all { it.kind == MarkdownBlock.Kind.Bullet })
        assertEquals("one", blocks[0].text)
        assertEquals("two", blocks[1].text)
        assertEquals("three", blocks[2].text)
    }

    @Test
    fun orderedList() {
        val blocks = Markdown.parse("1. first\n2. second")
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it.kind == MarkdownBlock.Kind.Bullet })
        assertEquals("first", blocks[0].text)
    }

    @Test
    fun divider() {
        val blocks = Markdown.parse("above\n\n---\n\nbelow")
        assertEquals(3, blocks.size)
        assertEquals("above", blocks[0].text)
        assertEquals(MarkdownBlock.Kind.Divider, blocks[1].kind)
        assertEquals("below", blocks[2].text)
    }

    @Test
    fun linksKeepLabelDropUrl() {
        val blocks = Markdown.parse("see [meshcore-mobile](https://example.com/x)")
        assertEquals(1, blocks.size)
        assertEquals("see meshcore-mobile", blocks[0].text)
    }

    @Test
    fun imagesAreDropped() {
        val blocks = Markdown.parse("alt text ![pic](x.png) trailing")
        assertEquals(1, blocks.size)
        assertEquals("alt text trailing", blocks[0].text)
    }

    @Test
    fun linkedImageCollapsesToEmpty() {
        // Outer link's label is the image itself; image is dropped, leaving
        // no visible text → no block emitted.
        val blocks =
            Markdown.parse("[![ci](https://img/badge.svg)](https://ci.example/run)")
        assertTrue(blocks.isEmpty(), "expected no block, got $blocks")
    }

    @Test
    fun emphasisIsStripped() {
        assertEquals("bold", Markdown.parse("**bold**").single().text)
        assertEquals("bold", Markdown.parse("__bold__").single().text)
        assertEquals("italic", Markdown.parse("*italic*").single().text)
        assertEquals("italic", Markdown.parse("_italic_").single().text)
        assertEquals("strike", Markdown.parse("~~strike~~").single().text)
        assertEquals("code", Markdown.parse("`code`").single().text)
    }

    @Test
    fun underscoreInsideWordIsKept() {
        assertEquals("snake_case_name", Markdown.parse("snake_case_name").single().text)
    }

    @Test
    fun fencedCodeBlocksAreFlattened() {
        val blocks = Markdown.parse("before\n\n```\ncode line\n```\n\nafter")
        assertEquals(3, blocks.size)
        assertEquals("before", blocks[0].text)
        assertEquals("code line", blocks[1].text)
        assertEquals("after", blocks[2].text)
    }

    @Test
    fun blankAndOrphanHashAreSkipped() {
        val blocks = Markdown.parse("\n##\n   \nhello\n")
        assertEquals(1, blocks.size)
        assertEquals("hello", blocks[0].text)
        assertEquals(MarkdownBlock.Kind.Paragraph, blocks[0].kind)
    }

    @Test
    fun blockquoteIsFlattened() {
        val blocks = Markdown.parse("> quoted line")
        assertEquals(1, blocks.size)
        assertEquals(MarkdownBlock.Kind.Paragraph, blocks[0].kind)
        assertEquals("quoted line", blocks[0].text)
    }

    @Test
    fun realWorldReadme() {
        val src = """
            ## meshcore-mobile

            [meshcore-mobile](https://github.com/yschimke/meshcore-mobile)

            _Mobile companion for MeshCore_

            [![ci](https://img/badge.svg)](https://ci/url)
        """.trimIndent()
        val blocks = Markdown.parse(src)
        // heading + link + italic-line + (linked image collapses to empty → dropped)
        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Kind.Heading, blocks[0].kind)
        assertEquals("meshcore-mobile", blocks[0].text)
        assertEquals("meshcore-mobile", blocks[1].text)
        assertEquals("Mobile companion for MeshCore", blocks[2].text)
    }
}
