package ee.schimke.ha.designs

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import ee.schimke.ha.design.DesignManifest
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.CardTemplatePlayback
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.components.HaAction
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The library the design server publishes is generated on the JVM (`generateCardDesigns`), and the
 * phone app plays it with AndroidX's player. For a sample of its designs, one per card type and
 * shape (spread across every theme), this plays the JVM's bytes and the same key captured here on
 * Android with the app's player, and checks they draw the same pixels.
 *
 * Runs against the library at `cardDesignsJvmDir` (`build/card-designs`, as
 * `:card-designs:generateCardDesigns` writes it); without one there is nothing to check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JvmLibraryAndroidPlaybackTest {

  @get:Rule val compose = createComposeRule()

  private val context = RuntimeEnvironment.getApplication()

  @Test
  fun theJvmLibraryDrawsOnAndroidAsAndroidsOwnCapture() {
    val dir =
      System.getProperty("cardDesignsJvmDir")?.let(::File)?.takeIf {
        it.resolve("manifest.json").exists()
      }
    assumeTrue("no JVM library generated (generateCardDesigns): nothing to check", dir != null)
    val manifest =
      json.decodeFromString(
        DesignManifest.serializer(),
        requireNotNull(dir).resolve("manifest.json").readText(),
      )
    val sample =
      manifest.designs
        .groupBy { it.key.cardType to it.key.shape }
        .values
        .mapIndexed { i, entries -> entries[i % entries.size] }
    val different = sample.filterNot { entry ->
      val jvm = dir.resolve(DesignManifest.blobPath(entry.sha256)).readBytes()
      val android = CheckedInCardDesigns.capture(context, entry.key).bytes
      val drawn = render(jvm)
      // Two blank drawings would match: the design must draw something.
      assertTrue(drawn.pixels().distinct().size > 1, "${entry.key} draws nothing")
      samePixels(drawn, render(android))
    }
    println(
      "JVM library on Android: ${sample.size - different.size} of ${sample.size} draw the same"
    )
    assertEquals(
      emptyList(),
      different.map { "${it.key.cardType} ${it.key.shape} ${it.key.theme}" },
    )
  }

  private var current by mutableStateOf<ByteArray?>(null)
  private var contentSet = false

  /** [bytes] as published, nothing bound, at card width in the app's player. */
  private fun render(bytes: ByteArray): Bitmap {
    if (!contentSet) {
      compose.setContent {
        val document = current ?: return@setContent
        Box(Modifier.width(CardPreviewRenderer.CARD_WIDTH_DP.dp).testTag("card")) {
          key(document) {
            CachedCardPreview(
              cacheKey = document,
              profile = androidXExperimentalWrap,
              card = null,
              snapshot = null,
              template = CardTemplatePlayback(document, { _, _ -> NO_BINDINGS }, HaAction.None),
            ) {}
          }
        }
      }
      contentSet = true
    }
    compose.runOnIdle { current = bytes }
    compose.waitForIdle()
    return compose.onNodeWithTag("card").captureToImage().asAndroidBitmap()
  }

  private fun Bitmap.pixels(): IntArray =
    IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

  private fun samePixels(a: Bitmap, b: Bitmap): Boolean {
    if (a.width != b.width || a.height != b.height) return false
    return a.pixels().contentEquals(b.pixels())
  }

  private companion object {
    val NO_BINDINGS = CardSnapshotBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap())
    val json = Json { ignoreUnknownKeys = true }
  }
}
