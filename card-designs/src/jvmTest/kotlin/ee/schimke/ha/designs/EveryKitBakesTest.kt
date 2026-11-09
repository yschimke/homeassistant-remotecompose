package ee.schimke.ha.designs

import ee.schimke.ha.rc.CommonCaptureHost
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A generator-like bake of every card style on a plain JVM, as `generateCardDesigns` captures the
 * library: each card type the library sources show, in each style and mode. A card part that can't
 * be drawn here (#1464: an `ImageBitmap` with no Compose graphics registered) fails this PR's unit
 * tests rather than only the "Library and server" job.
 */
class EveryKitBakesTest {
  @Test
  fun everyCardStyleCapturesEveryCardTypeOnAPlainJvm() {
    JvmComposeGraphics.ensureRegistered()
    // One key per card type, style and mode: what differs between kits, without every shape.
    val keys =
      CardDesignLibrary.librarySources()
        .flatMap { CardDesignLibrary.keysOf(it.card, it.snapshot) }
        .distinctBy { Triple(it.cardType, it.theme, it.variant) }
    assertTrue(keys.isNotEmpty())
    val host = CommonCaptureHost(LibraryCaptureProfile.OPERATIONS)
    val failures = keys.mapNotNull { key ->
      runCatching { CheckedInCardDesigns.capture(host, key) }.exceptionOrNull()?.let { "$key: $it" }
    }
    assertTrue(
      failures.isEmpty(),
      "${failures.size} of ${keys.size} failed:\n" + failures.take(20).joinToString("\n"),
    )
  }
}
