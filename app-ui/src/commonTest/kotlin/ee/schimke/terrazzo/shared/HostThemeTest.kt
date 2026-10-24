package ee.schimke.terrazzo.shared

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostThemeTest {
  @Test
  fun parsesCanvasNormalizedColors() {
    assertEquals(Color(0xFF03A9F4), parseCssColor("#03a9f4"))
    assertEquals(Color(0xFFFFFFFF), parseCssColor("#fff"))
    assertEquals(Color(0x80112233), parseCssColor("#11223380"))
    assertEquals(Color(0f, 0f, 0f, 0.12f), parseCssColor("rgba(0, 0, 0, 0.12)"))
    assertEquals(Color(0xFF1C1C1C), parseCssColor("rgb(28, 28, 28)"))
  }

  @Test
  fun rejectsWhatIsNotAColor() {
    assertNull(parseCssColor(""))
    assertNull(parseCssColor("var(--primary-color)"))
    assertNull(parseCssColor("#12345"))
    assertNull(parseCssColor("rgb(1, 2)"))
  }

  @Test
  fun readsThePanelsTheme() {
    val theme =
      HostTheme.parse(
        """{"dark":true,"primary":"#ff9800","background":"#111111","card":"","text":"#e1e1e1"}"""
      )
    assertEquals(true, theme?.dark)
    assertEquals(Color(0xFFFF9800), theme?.primary)
    assertNull(theme?.card)
    assertNull(theme?.divider)
    assertNull(HostTheme.parse(""))
    assertNull(HostTheme.parse("not json"))
  }
}
