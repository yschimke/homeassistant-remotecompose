package ee.schimke.ha.rc.cards

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DecimalTextTest {
  private val original = Locale.getDefault()

  @AfterTest
  fun restoreLocale() {
    Locale.setDefault(original)
  }

  @Test
  fun usesADotWhateverTheDeviceLocale() {
    Locale.setDefault(Locale.GERMANY)
    assertEquals("21.5", decimalText(21.5, 1))
    assertEquals("0.33", decimalText(1.0 / 3, 2))
  }

  @Test
  fun wholeNumbersHaveNoDecimals() {
    assertEquals("21", decimalText(21.0, 1))
    assertEquals("-3", decimalText(-3.0, 2))
  }

  @Test
  fun floatTemperaturesRoundToTheirShortForm() {
    assertEquals("21.3", decimalText(21.3f.toDouble(), 1))
  }
}
