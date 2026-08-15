package ee.schimke.ha.rc

import androidx.compose.remote.core.Operations
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetProfileTest {

  @Test
  fun android16Profile_excludesColorTheme() {
    assertFalse(Operations.COLOR_THEME in widgetsProfile.supportedOperations)
  }

  @Test
  fun systemThemedProfile_includesColorTheme() {
    assertTrue(Operations.COLOR_THEME in systemThemedWidgetsProfile.supportedOperations)
  }
}
