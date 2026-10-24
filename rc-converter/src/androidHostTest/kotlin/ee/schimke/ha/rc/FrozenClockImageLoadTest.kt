package ee.schimke.ha.rc

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.RemoteHaImageUrl
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Under a frozen clock (previews, tests) the player decodes a plain [BitmapLoader]'s images inline,
 * so the image is drawn by the time the UI is idle, however slow the loader is. On `Dispatchers.IO`
 * nothing a capture waits on waited for it: a busy render run sometimes captured the placeholder
 * (the Climate dashboard's camera, a GitHub badge).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrozenClockImageLoadTest {

  @get:Rule val compose = createComposeRule()

  /** A solid magenta image, handed over only after [delayMs]: a loader slower than a frame. */
  private class SlowLoader(private val delayMs: Long) : BitmapLoader {
    private val bytes: ByteArray =
      ByteArrayOutputStream().use { out ->
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(android.graphics.Color.MAGENTA)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
      }

    override fun loadBitmap(name: String): ByteArrayInputStream {
      Thread.sleep(delayMs)
      return ByteArrayInputStream(bytes)
    }
  }

  @Test
  fun aFrozenClockDrawsTheImageBeforeTheUiIsIdle() {
    val loader = SlowLoader(delayMs = 500)
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, false)
    compose.setContent {
      CompositionLocalProvider(
        LocalHaClock provides
          FixedHaClock(ZonedDateTime.of(2026, 5, 5, 10, 8, 0, 0, ZoneOffset.UTC))
      ) {
        Box(Modifier.background(Color.White).width(320.dp).testTag("card")) {
          CachedCardPreview(
            cacheKey = "frozen-image",
            profile = androidXExperimentalWrap,
            bitmapLoader = loader,
          ) {
            ProvideHaTheme(theme) {
              RemoteHaImageUrl(
                url = "https://example.com/camera.jpg",
                contentDescription = "camera".rs,
                modifier = RemoteModifier.fillMaxWidth().height(120.rdp),
              )
            }
          }
        }
      }
    }
    compose.waitForIdle()

    val pixels = compose.onNodeWithTag("card").captureToImage().toPixelMap()
    var magenta = 0
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
      val c = pixels[x, y]
      if (c.red > 0.9f && c.blue > 0.9f && c.green < 0.1f) magenta++
    }
    assertTrue(magenta > 100, "the loaded image is drawn when the UI is idle ($magenta px)")
  }
}
