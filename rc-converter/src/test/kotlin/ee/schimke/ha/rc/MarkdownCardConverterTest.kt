package ee.schimke.ha.rc

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.MarkdownCardConverter
import kotlin.test.Test
import kotlin.test.assertEquals

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

        assertEquals("Temperature: __HA_EXPR_0__", template.rendered)
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

        assertEquals("Temperature:\n__HA_EXPR_0__", template.rendered)
    }

    @Test
    fun markdownTemplateBindingSupportsMultipleExpressions() {
        val template =
            MarkdownCardConverter.MarkdownTemplateBindings.from(
                "Temp {{ states('sensor.living_room_temperature') }} / Humidity {{ states('sensor.office_humidity') }}",
                snapshot,
            )

        assertEquals("Temp __HA_EXPR_0__ / Humidity __HA_EXPR_1__", template.rendered)
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
    fun templatesCanBeEmbeddedInMarkdownFormatting() {
        val template =
            MarkdownCardConverter.MarkdownTemplateBindings.from(
                "- **Temp**: {{ states('sensor.living_room_temperature') }} °C",
                snapshot,
            )

        assertEquals("- **Temp**: __HA_EXPR_0__ °C", template.rendered)
    }
}
