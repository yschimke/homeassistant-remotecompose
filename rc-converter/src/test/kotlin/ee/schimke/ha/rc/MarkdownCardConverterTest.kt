package ee.schimke.ha.rc

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.MarkdownCardConverter
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownCardConverterTest {

    @Test
    fun markdownTemplateBindingSupportsMultilineExpressions() {
        val snapshot =
            HaSnapshot(
                states =
                    mapOf(
                        "sensor.living_room_temperature" to
                            EntityState("sensor.living_room_temperature", "21.5")
                    )
            )

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
}
