package ee.schimke.ha.designs

import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.terrazzo.shared.RcSafeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Safe mode (the run after a crash) only plays a document once it passes [RcSafeMode.check], so a
 * check that rejected a healthy document would disable a working card. Every card type the library
 * captures, in each of its variants and shapes, must pass it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SafeModeCheckTest {
  private val context = RuntimeEnvironment.getApplication()

  @Test
  fun everyLibraryDocumentPassesTheSafeModeCheck() {
    val keys =
      GenerateCardDesignLibrary.librarySources()
        .flatMap { GenerateCardDesignLibrary.keysOf(it.card, it.snapshot) }
        .distinct()
        .distinctBy { Triple(it.cardType, it.variant, it.shape) }
    assertTrue(keys.size > 10, "the library has card keys to check")
    val failures = keys.mapNotNull { key ->
      RcSafeMode.check(CardDesignTemplates.capture(context, key).bytes)?.let {
        "${key.cardType} ${key.variant} ${key.theme}: $it"
      }
    }
    println("safe-mode check: ${keys.size} documents")
    assertEquals(emptyList(), failures)
  }
}
