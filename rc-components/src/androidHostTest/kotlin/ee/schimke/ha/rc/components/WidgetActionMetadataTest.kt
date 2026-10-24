package ee.schimke.ha.rc.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetActionMetadataTest {

  @Test
  fun rawActionPayloadRemainsCompatible() {
    val raw = """{"type":"toggle"}"""

    assertEquals(WidgetActionMetadata(actionPayload = raw), decodeWidgetActionMetadata(raw))
  }

  @Test
  fun enrichedMetadataSeparatesCurrentDocumentContext() {
    val raw =
      """{"type":"toggle"}""" +
        "\n--RC-METADATA-V1--\nlight.kitchen" +
        "\n--RC-VALUE--\nOn" +
        "\n--RC-TIME--\n21:44:02"

    assertEquals(
      WidgetActionMetadata(
        actionPayload = """{"type":"toggle"}""",
        entityId = "light.kitchen",
        currentValue = "On",
        documentTime = "21:44:02",
      ),
      decodeWidgetActionMetadata(raw),
    )
  }

  @Test
  fun nullMetadataStaysNull() {
    assertNull(decodeWidgetActionMetadata(null))
  }
}
