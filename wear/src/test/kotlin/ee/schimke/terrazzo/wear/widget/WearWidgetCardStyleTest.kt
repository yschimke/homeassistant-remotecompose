package ee.schimke.terrazzo.wear.widget

import kotlin.test.Test
import kotlin.test.assertEquals

class WearWidgetCardStyleTest {
  @Test
  fun explicitPhoneStyleWinsOverTheWatchStyle() {
    assertEquals("lcd", wearWidgetCardStyle("lcd", "braun").id)
    assertEquals("classic", wearWidgetCardStyle("classic", "console").id)
  }

  @Test
  fun absentClearedAndUnknownOverridesFollowTheWatchStyle() {
    for (override in listOf(null, "", "future-style")) {
      assertEquals("braun", wearWidgetCardStyle(override, "braun").id)
    }
    assertEquals("classic", wearWidgetCardStyle("future-style", "future-watch-style").id)
  }
}
