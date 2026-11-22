package ee.schimke.ha.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every device pack design can be drawn in this process ([CardDesignDocument]): each node is a
 * component this host draws, with literal or declared-state properties, so `:previews` renders all
 * of them and a pack change shows in the preview diff.
 */
class DevicePackRenderTest {
  @Test
  fun everyPackDesignIsBundled() {
    assertEquals(12, DevicePacks.paths.size, DevicePacks.paths.toString())
    assertTrue(DevicePacks.paths.all { DevicePacks.design(it) != null })
  }

  @Test
  fun everyPackDesignCanBeDrawnHere() {
    val problems =
      DevicePacks.paths.flatMap { path ->
        DevicePacks.design(path)!!.validate().map { "$path: $it" }
      }
    assertEquals(emptyList(), problems)
  }
}
