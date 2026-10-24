@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteImage
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ee.schimke.ha.rc.components.rememberLocalNamedRemoteBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Upstream observation for #277, kept separate from any app behaviour: does
 * `RemoteComposePlayer.setUserLocalBitmap` replace a named bitmap the document already drew?
 *
 * No production path depends on it (pictures load through the player's URL loader, and widgets bake
 * their bytes), so the test asserts only that the document's own bitmap draws, and prints what the
 * override does. It passes either way; read `BITMAP_OVERRIDE` in its output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NamedBitmapOverrideTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private fun solid(color: Int): Bitmap =
    Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

  private fun draw(player: RemoteComposePlayer): Int {
    player.measure(
      View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
    )
    player.layout(0, 0, 100, 100)
    val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    player.draw(Canvas(image))
    return image.getPixel(50, 50).also { image.recycle() }
  }

  @Test
  fun userLocalBitmapReplacesTheDrawnBitmap() {
    val red = android.graphics.Color.rgb(183, 28, 28)
    val blue = android.graphics.Color.rgb(21, 101, 192)
    val bytes = runBlocking {
      captureSingleRemoteDocument(
          context = compose.activity,
          creationDisplayInfo = RemoteCreationDisplayInfo(100, 100, 160),
          profile = androidXExperimental,
        ) {
          val bitmap = rememberLocalNamedRemoteBitmap(NAME) { solid(red).asImageBitmap() }
          RemoteImage(
            remoteBitmap = bitmap,
            contentDescription = null,
            modifier = RemoteModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
          )
        }
        .bytes
    }
    compose.runOnIdle {
      val player =
        RemoteComposePlayer(compose.activity).apply {
          setDocument(
            RemoteDocument(
              CoreDocument().apply {
                initFromBuffer(RemoteComposeBuffer.fromInputStream(bytes.inputStream()))
              }
            )
          )
        }
      assertEquals(red, draw(player), "the document's own bitmap draws")
      player.setUserLocalBitmap(NAME, solid(blue))
      assertEquals(blue, draw(player), "the override replaces it")
    }
  }

  private companion object {
    const val NAME = "repro.bitmap"
  }
}
