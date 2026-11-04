package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.terrazzo.core.prefs.CardStylePref
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The app's stored card style ids are the card styles: a style added to [HaUberTheme.all] (a new
 * kit, say) needs its preference entry, and the order of the Settings list follows the catalogue.
 */
class CardStyleListsTest {
  @Test
  fun everyCardStyleHasAPreferenceInOrder() {
    assertEquals(HaUberTheme.all.map { it.id }, CardStylePref.entries.map { it.id })
  }
}
