package ee.schimke.ha.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class TemplateBindingsTest {

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  // Trimmed-down version of the "Card Tracker — Am I close to home?"
  // card from the bug report: statement blocks, filters and state_attr.
  private val scriptyTemplate =
    """
    {% set e = 'binary_sensor.meshcore_contact' %}
    {% set lat = state_attr(e, 'latitude') %}
    {% if states(e) == 'on' %} 🟢 Online {% else %} 🔴 Stale {% endif %}
    {{ d | round0 | int }} km from home
    """
      .trimIndent()

  @Test
  fun needsTemplateRender_flagsStatementBlocks() {
    assertTrue(TemplateBindings.needsTemplateRender(scriptyTemplate))
    assertTrue(TemplateBindings.needsTemplateRender("{% if true %}x{% endif %}"))
    assertTrue(TemplateBindings.needsTemplateRender("{# a comment #}"))
  }

  @Test
  fun needsTemplateRender_flagsNonTrivialExpressions() {
    assertTrue(TemplateBindings.needsTemplateRender("{{ state_attr('x', 'y') }}"))
    assertTrue(TemplateBindings.needsTemplateRender("{{ states('x') | round(1) }}"))
    // Double-quoted states() isn't handled by the simple binding either.
    assertTrue(TemplateBindings.needsTemplateRender("""{{ states("x") }}"""))
  }

  @Test
  fun needsTemplateRender_leavesSimpleContentToTheLiveBinding() {
    assertFalse(TemplateBindings.needsTemplateRender("## Notes\nplain text"))
    assertFalse(TemplateBindings.needsTemplateRender("Temp: {{ states('sensor.t') }}"))
    assertFalse(
      TemplateBindings.needsTemplateRender(
        "A {{ states('sensor.a') }} / B {{ states('sensor.b') }}"
      )
    )
  }

  @Test
  fun templateKey_isStableAndCollapsesWhitespaceEquivalentSources() {
    val a = TemplateBindings.templateKey(scriptyTemplate)
    val b = TemplateBindings.templateKey("\n$scriptyTemplate\n  ")
    assertEquals(a, b)
    assertEquals(a, TemplateBindings.templateKey(scriptyTemplate))
  }

  @Test
  fun templateKey_differsForDifferentTemplates() {
    assertTrue(
      TemplateBindings.templateKey("{% if a %}1{% endif %}") !=
        TemplateBindings.templateKey("{% if b %}2{% endif %}")
    )
  }

  @Test
  fun cardTemplates_extractsServerRenderMarkdownOnly() {
    val card =
      json.decodeFromString(
        CardConfig.serializer(),
        """{"type":"markdown","content":"{% if states('x') == 'on' %}on{% endif %}"}""",
      )
    val refs = TemplateBindings.cardTemplates(card)
    assertEquals(1, refs.size)
    assertEquals(TemplateBindings.templateKey(refs[0].template), refs[0].key)

    val simple =
      json.decodeFromString(
        CardConfig.serializer(),
        """{"type":"markdown","content":"Temp {{ states('sensor.t') }}"}""",
      )
    assertTrue(TemplateBindings.cardTemplates(simple).isEmpty())
  }

  @Test
  fun cardTemplates_recursesNestedStacksAndDeduplicates() {
    val tpl = "{% if states('x') == 'on' %}on{% endif %}"
    val card =
      json.decodeFromString(
        CardConfig.serializer(),
        """
        {
          "type": "vertical-stack",
          "cards": [
            {"type": "markdown", "content": "$tpl"},
            {"type": "horizontal-stack", "cards": [
              {"type": "markdown", "content": "$tpl"},
              {"type": "markdown", "content": "{% set y = 1 %}{{ y }}"}
            ]}
          ]
        }
        """
          .trimIndent(),
      )
    val refs = TemplateBindings.cardTemplates(card)
    // Two distinct templates; the repeated one collapses to a single ref.
    assertEquals(2, refs.size)
  }

  @Test
  fun dashboardTemplates_walksViewsAndSections() {
    val dashboard =
      json.decodeFromString(
        Dashboard.serializer(),
        """
        {
          "views": [
            {
              "cards": [
                {"type": "markdown", "content": "{% if states('a') %}1{% endif %}"}
              ],
              "sections": [
                {"cards": [
                  {"type": "markdown", "content": "{% if states('b') %}2{% endif %}"},
                  {"type": "markdown", "content": "no template here"}
                ]}
              ]
            }
          ]
        }
        """
          .trimIndent(),
      )
    val refs = TemplateBindings.dashboardTemplates(dashboard)
    assertEquals(2, refs.size)
  }
}
