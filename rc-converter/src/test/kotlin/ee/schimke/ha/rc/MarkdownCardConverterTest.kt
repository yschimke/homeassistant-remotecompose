package ee.schimke.ha.rc

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.MarkdownCardConverter
import ee.schimke.ha.rc.cards.haExprToken
import ee.schimke.ha.rc.components.Markdown
import ee.schimke.ha.rc.components.MarkdownInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarkdownCardConverterTest {

    private val snapshot =
        HaSnapshot(
            states =
                mapOf(
                    "sensor.living_room_temperature" to
                        EntityState("sensor.living_room_temperature", "21.5"),
                    "sensor.office_humidity" to EntityState("sensor.office_humidity", "44"),
                )
        )

    @Test
    fun markdownWithoutTemplatesIsUnchanged() {
        val content = "## Notes\nNo templates here."

        val template = MarkdownCardConverter.MarkdownTemplateBindings.from(content, snapshot)

        assertEquals(content, template.rendered)
    }

    @Test
    fun markdownTemplateBindingSupportsSingleLineExpressions() {
        val template =
            MarkdownCardConverter.MarkdownTemplateBindings.from(
                "Temperature: {{ states('sensor.living_room_temperature') }}",
                snapshot,
            )

        assertEquals("Temperature: ${haExprToken(0)}", template.rendered)
    }

    @Test
    fun markdownTemplateBindingSupportsMultilineExpressions() {
        val template =
            MarkdownCardConverter.MarkdownTemplateBindings.from(
                """
                Temperature:
                {{
                  states('sensor.living_room_temperature')
                }}
                """.trimIndent(),
                snapshot,
            )

        assertEquals("Temperature:\n${haExprToken(0)}", template.rendered)
    }

    @Test
    fun markdownTemplateBindingSupportsMultipleExpressions() {
        val template =
            MarkdownCardConverter.MarkdownTemplateBindings.from(
                "Temp {{ states('sensor.living_room_temperature') }} / Humidity {{ states('sensor.office_humidity') }}",
                snapshot,
            )

        assertEquals(
            "Temp ${haExprToken(0)} / Humidity ${haExprToken(1)}",
            template.rendered,
        )
    }

    @Test
    fun unsupportedTemplateExpressionsRemainLiteral() {
        val content = "Value: {{ state_attr('sensor.office_humidity', 'friendly_name') }}"

        val template = MarkdownCardConverter.MarkdownTemplateBindings.from(content, snapshot)

        assertEquals(content, template.rendered)
    }

    @Test
    fun malformedTemplateExpressionsRemainLiteral() {
        val content = "Temperature {{ states('sensor.living_room_temperature') "

        val template = MarkdownCardConverter.MarkdownTemplateBindings.from(content, snapshot)

        assertEquals(content, template.rendered)
    }

    @Test
    fun inlineTemplateTokenNextToLinkStaysBound() {
        // A line mixing a simple {{ states('x') }} binding with a link takes
        // the rich flow-row path (hasInlineMarkup). The tokenised text run
        // must survive markdown parsing and resolve to a live binding rather
        // than rendering the literal token. This mirrors
        // MarkdownCardConverter.bind, which calls template.bind per inline run.
        val content =
            "{{ states('sensor.living_room_temperature') }} [history](/lovelace/history)"
        val template = MarkdownCardConverter.MarkdownTemplateBindings.from(content, snapshot)
        assertEquals("${haExprToken(0)} [history](/lovelace/history)", template.rendered)

        val block = Markdown.parse(template.rendered).single()
        assertTrue(block.hasInlineMarkup)

        // The token survives parsing intact (a `__…__` token would be eaten
        // as bold emphasis and the binding lost).
        val textRun = block.inlines.filterIsInstance<MarkdownInline.Text>().single()
        assertTrue(textRun.text.contains(haExprToken(0)))
        // Non-null bound string ⇒ the run renders the live state, not the token.
        assertNotNull(template.bind(textRun.text))

        val link = block.inlines.filterIsInstance<MarkdownInline.Link>().single()
        assertEquals("history", link.text)
        assertEquals("/lovelace/history", link.url)
    }

    @Test
    fun plainTemplateTokenSurvivesParsingAndStaysBound() {
        // Regression: the binding token must not be markdown-active. A
        // `__…__` token parsed as bold emphasis, stripping the delimiters
        // so the bind regex missed it and the card showed the literal text.
        val template =
            MarkdownCardConverter.MarkdownTemplateBindings.from(
                "Temperature: {{ states('sensor.living_room_temperature') }}",
                snapshot,
            )
        val block = Markdown.parse(template.rendered).single()
        assertTrue(block.text.contains(haExprToken(0)), "token was altered by parsing: ${block.text}")
        assertNotNull(template.bind(block.text))
    }

    @Test
    fun templatesCanBeEmbeddedInMarkdownFormatting() {
        val template =
            MarkdownCardConverter.MarkdownTemplateBindings.from(
                "- **Temp**: {{ states('sensor.living_room_temperature') }} °C",
                snapshot,
            )

        assertEquals("- **Temp**: ${haExprToken(0)} °C", template.rendered)
    }
}
