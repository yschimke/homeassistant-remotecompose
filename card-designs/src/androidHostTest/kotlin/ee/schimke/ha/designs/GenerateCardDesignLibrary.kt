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
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.CardTemplatePlayback
import ee.schimke.ha.rc.androidXExperimentalWrap
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Generates the card design library ([CardDesignLibrary]) into `cardDesignsOut` (see
 * build.gradle.kts) on Android: captured with AndroidX, its previews drawn by the app's player.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenerateCardDesignLibrary {

  @get:Rule val compose = createComposeRule()

  @Test
  fun generate() {
    val target = File(requireNotNull(System.getProperty("cardDesignsOut")) { "no cardDesignsOut" })
    val count = CardDesignLibrary.generate(target, RuntimeEnvironment.getApplication(), ::render)
    println("card designs: $count designs in $target")
  }

  private class Render(
    val template: CardTemplatePlayback,
    val card: CardConfig?,
    val snapshot: HaSnapshot?,
  )

  private var current by mutableStateOf<Render?>(null)
  private var contentSet = false

  /** [playback] at card width, one fresh player per document. */
  private fun render(
    playback: CardTemplatePlayback,
    card: CardConfig?,
    snapshot: HaSnapshot?,
  ): ByteArray {
    if (!contentSet) {
      compose.setContent {
        val render = current ?: return@setContent
        Box(Modifier.width(CardPreviewRenderer.CARD_WIDTH_DP.dp).testTag("card")) {
          key(render) {
            CachedCardPreview(
              cacheKey = render,
              profile = androidXExperimentalWrap,
              card = render.card,
              snapshot = render.snapshot,
              template = render.template,
            ) {}
          }
        }
      }
      contentSet = true
    }
    compose.runOnIdle { current = Render(playback, card, snapshot) }
    compose.waitForIdle()
    val image = compose.onNodeWithTag("card").captureToImage().asAndroidBitmap()
    return ByteArrayOutputStream()
      .also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
      .toByteArray()
  }
}
