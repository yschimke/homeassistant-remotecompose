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
  fun linksKeepLabelAndUrl() {
    val blocks = Markdown.parse("see [meshcore-mobile](https://example.com/x)")
    assertEquals(1, blocks.size)
    assertEquals("see meshcore-mobile", blocks[0].text)
    assertTrue(blocks[0].hasInlineMarkup)
    val link = blocks[0].inlines.filterIsInstance<MarkdownInline.Link>().single()
    assertEquals("meshcore-mobile", link.text)
    assertEquals("https://example.com/x", link.url)
  }

  @Test
  fun imagesAreKept() {
    val blocks = Markdown.parse("alt text ![pic](x.png) trailing")
    assertEquals(1, blocks.size)
    assertEquals("alt text trailing", blocks[0].text)
    val image = blocks[0].inlines.filterIsInstance<MarkdownInline.Image>().single()
    assertEquals("x.png", image.url)
    assertEquals("pic", image.alt)
  }

  @Test
  fun linkedImageBecomesLinkedBadge() {
    // `[![ci](src)](href)` — the image survives as a badge that links
    // to the outer href; it contributes no visible text.
    val blocks = Markdown.parse("[![ci](https://img/badge.svg)](https://ci.example/run)")
    assertEquals(1, blocks.size)
    assertEquals("", blocks[0].text)
    assertTrue(blocks[0].hasInlineMarkup)
    val image = blocks[0].inlines.filterIsInstance<MarkdownInline.Image>().single()
    assertEquals("https://img/badge.svg", image.url)
    assertEquals("https://ci.example/run", image.href)
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
    val src =
      """
      ## meshcore-mobile

      [meshcore-mobile](https://github.com/yschimke/meshcore-mobile)

      _Mobile companion for MeshCore_

      [![ci](https://img/badge.svg)](https://ci/url)
      """
        .trimIndent()
    val blocks = Markdown.parse(src)
    // heading + link-line + italic-line + linked-badge image
    assertEquals(4, blocks.size)
    assertEquals(MarkdownBlock.Kind.Heading, blocks[0].kind)
    assertEquals("meshcore-mobile", blocks[0].text)
    assertEquals("meshcore-mobile", blocks[1].text)
    assertEquals(
      "https://github.com/yschimke/meshcore-mobile",
      blocks[1].inlines.filterIsInstance<MarkdownInline.Link>().single().url,
    )
    assertEquals("Mobile companion for MeshCore", blocks[2].text)
    assertEquals(MarkdownStyle(italic = true), blocks[2].style)
    val badge = blocks[3].inlines.filterIsInstance<MarkdownInline.Image>().single()
    assertEquals("https://img/badge.svg", badge.url)
    assertEquals("https://ci/url", badge.href)
  }

  @Test
  fun headingLinkIsClickable() {
    val blocks = Markdown.parse("## [repo](https://example.com/repo)")
    assertEquals(1, blocks.size)
    assertEquals(MarkdownBlock.Kind.Heading, blocks[0].kind)
    assertTrue(blocks[0].hasInlineMarkup)
    assertEquals(
      "https://example.com/repo",
      blocks[0].inlines.filterIsInstance<MarkdownInline.Link>().single().url,
    )
  }

  @Test
  fun badgeRowKeepsEveryBadge() {
    val src =
      "[![ci](https://img/ci.svg)](https://ci) " + "[![fmt](https://img/fmt.svg)](https://fmt)"
    val blocks = Markdown.parse(src)
    assertEquals(1, blocks.size)
    val images = blocks[0].inlines.filterIsInstance<MarkdownInline.Image>()
    assertEquals(2, images.size)
    assertEquals("https://img/ci.svg", images[0].url)
    assertEquals("https://img/fmt.svg", images[1].url)
  }

  @Test
  fun plainParagraphHasNoInlineMarkup() {
    val blocks = Markdown.parse("just plain text")
    assertEquals(1, blocks.size)
    assertTrue(!blocks[0].hasInlineMarkup)
    assertEquals(MarkdownStyle.Plain, blocks[0].style)
  }

  // --- emphasis ------------------------------------------------------------

  private fun runs(source: String): List<Pair<String, MarkdownStyle>> =
    Markdown.parse(source).single().inlines.map {
      val text = it as MarkdownInline.Text
      text.text to text.style
    }

  private val italic = MarkdownStyle(italic = true)
  private val bold = MarkdownStyle(bold = true)
  private val both = MarkdownStyle(bold = true, italic = true)

  @Test
  fun asteriskAndUnderscoreItalic() {
    assertEquals(
      listOf("a " to MarkdownStyle.Plain, "b" to italic, " c" to MarkdownStyle.Plain),
      runs("a *b* c"),
    )
    assertEquals(
      listOf("a " to MarkdownStyle.Plain, "b" to italic, " c" to MarkdownStyle.Plain),
      runs("a _b_ c"),
    )
  }

  @Test
  fun doubleAsteriskAndUnderscoreBold() {
    assertEquals(listOf("a " to MarkdownStyle.Plain, "b" to bold), runs("a **b**"))
    assertEquals(listOf("a " to MarkdownStyle.Plain, "b" to bold), runs("a __b__"))
  }

  @Test
  fun tripleAsteriskIsBoldAndItalic() {
    assertEquals(listOf("both" to both), runs("***both***"))
  }

  @Test
  fun nestedEmphasisCombines() {
    assertEquals(
      listOf("bold " to bold, "and italic" to both, " end" to bold),
      runs("**bold _and italic_ end**"),
    )
  }

  @Test
  fun inlineCodeIsMonospaceAndLiteral() {
    assertEquals(
      listOf("run " to MarkdownStyle.Plain, "a*b*c \\_" to MarkdownStyle.Code),
      runs("run `a*b*c \\_`"),
    )
  }

  @Test
  fun intrawordUnderscoreStaysPlain() {
    assertEquals(
      listOf("use snake_case_name here" to MarkdownStyle.Plain),
      runs("use snake_case_name here"),
    )
    // `*` is allowed intraword by CommonMark; `_` is not.
    assertEquals(
      listOf("foo" to MarkdownStyle.Plain, "bar" to italic, "baz" to MarkdownStyle.Plain),
      runs("foo*bar*baz"),
    )
  }

  @Test
  fun unmatchedMarkersStayLiteral() {
    assertEquals(listOf("2 * 3 = 6" to MarkdownStyle.Plain), runs("2 * 3 = 6"))
    assertEquals(listOf("*not closed" to MarkdownStyle.Plain), runs("*not closed"))
    assertEquals(listOf("__half" to MarkdownStyle.Plain), runs("__half"))
    // An empty emphasis is not emphasis: HA (marked) shows the delimiters.
    assertEquals(listOf("__" to MarkdownStyle.Plain), runs("__"))
    assertEquals(listOf("a `tick" to MarkdownStyle.Plain), runs("a `tick"))
  }

  @Test
  fun escapedMarkersAreLiteral() {
    assertEquals(listOf("*not italic*" to MarkdownStyle.Plain), runs("\\*not italic\\*"))
    assertEquals(listOf("_kept_" to MarkdownStyle.Plain), runs("\\_kept\\_"))
  }

  @Test
  fun wholeBlockEmphasisIsTheBlockStyle() {
    val block = Markdown.parse("_Mobile companion for MeshCore_").single()
    assertEquals(italic, block.style)
    assertTrue(!block.hasInlineMarkup, "a uniformly italic block stays a single text")
    // Whitespace between two runs of one style carries no style of its own.
    assertEquals(italic, Markdown.parse("*a* *b*").single().style)
  }

  @Test
  fun mixedEmphasisTakesTheInlinePath() {
    val block = Markdown.parse("just **bold** text").single()
    assertEquals("just bold text", block.text)
    assertTrue(block.hasInlineMarkup)
    assertEquals(MarkdownStyle.Plain, block.style)
  }

  @Test
  fun emphasisInHeadingsAndBullets() {
    val blocks = Markdown.parse("## **Title**\n\n- _one_\n- two")
    assertEquals(bold, blocks[0].style)
    assertEquals(italic, blocks[1].style)
    assertEquals(MarkdownStyle.Plain, blocks[2].style)
  }

  @Test
  fun fencedCodeLinesAreMonospace() {
    val blocks = Markdown.parse("```\ncode line\n```")
    assertEquals(MarkdownStyle.Code, blocks.single().style)
  }

  @Test
  fun italicIsParsedButDrawnUpright() {
    // Held back until rc-player-compose decodes the font-style field as italic.
    val mixed = Markdown.parse("a *b* c").single()
    assertEquals(
      MarkdownStyle(italic = true),
      mixed.inlines[1].let { (it as MarkdownInline.Text).style },
    )
    assertTrue(!mixed.hasInlineMarkup, "italic-only runs draw like plain text")
    assertEquals(MarkdownStyle.Plain, mixed.drawnStyle)
    assertEquals(MarkdownStyle(bold = true), Markdown.parse("***both***").single().drawnStyle)
    val boldAndItalic = Markdown.parse("***a*** **b**").single()
    assertTrue(!boldAndItalic.hasInlineMarkup)
    assertEquals(MarkdownStyle(bold = true), boldAndItalic.drawnStyle)
  }

  private fun link(source: String): MarkdownInline.Link =
    Markdown.parse(source).single().inlines.filterIsInstance<MarkdownInline.Link>().single()

  @Test
  fun emphasisAroundALinkStylesTheLabel() {
    val l = link("**[docs](https://example.com)**")
    assertEquals("docs", l.text)
    assertEquals("https://example.com", l.url)
    assertEquals(MarkdownStyle(bold = true), l.style)
  }

  @Test
  fun emphasisInsideALinkLabelStylesTheLabel() {
    assertEquals(MarkdownStyle(bold = true), link("[**docs**](https://example.com)").style)
    assertEquals(MarkdownStyle.Code, link("[`code`](https://example.com)").style)
    assertEquals("code", link("[`code`](https://example.com)").text)
  }

  @Test
  fun mixedLabelTakesItsDominantStyle() {
    assertEquals(MarkdownStyle(bold = true), link("[**longer** x](https://example.com)").style)
  }

  @Test
  fun plainLinkIsUnchanged() {
    val l = link("see [docs](https://example.com) here")
    assertEquals("docs", l.text)
    assertEquals(MarkdownStyle.Plain, l.style)
  }
}
