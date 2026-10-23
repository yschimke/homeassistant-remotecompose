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
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteStateLayout
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.ri
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.text.DecimalFormat
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Upstream observations for #224's three `RemoteStateLayout` defects, each against the current
 * Remote Compose, with none of `RemoteSizeBreakpoint`'s workarounds:
 * 1. `intOverload`: `RemoteStateLayout(RemoteInt, 0, 1)` picked `keys[0]` whatever the live index.
 * 2. `transitive`: a named `componentWidth()` expression read only through a derived boolean was
 *    never materialized, so the predicate stayed false.
 * 3. `overlay`: on a switch from the captured branch, the previous branch's children painted over
 *    the active one (#309).
 *
 * Each document paints red over its top half for the narrow branch and blue over its bottom half
 * for the wide one; a cell of 100 px is narrow and of 300 px wide (threshold 200 px, mdpi). The
 * tests pass either way: they print `STATE_LAYOUT <probe> <width> frame=<n> top=<red?>
 * bottom=<blue?>`, and correct output is top only when narrow, bottom only when wide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StateLayoutUpstreamEvidenceTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val red = Color(0xFFB71C1C)
  private val blue = Color(0xFF1565C0)

  @Composable
  private fun Branch(wide: Boolean) {
    RemoteBox(
      RemoteModifier.fillMaxSize(),
      contentAlignment = if (wide) RemoteAlignment.BottomStart else RemoteAlignment.TopStart,
    ) {
      RemoteBox(
        RemoteModifier.fillMaxWidth().height(25.rdp).background((if (wide) blue else red).rc)
      )
    }
  }

  private fun document(content: @Composable () -> Unit): ByteArray = runBlocking {
    captureSingleRemoteDocument(
        context = compose.activity,
        creationDisplayInfo = RemoteCreationDisplayInfo(300, 50, 160),
        profile = androidXExperimental,
      ) {
        RemoteBox(RemoteModifier.fillMaxSize()) { content() }
      }
      .bytes
  }

  @Composable
  private fun width(name: String, materialized: Boolean): RemoteFloat {
    val width =
      RemoteFloat.createNamedRemoteFloatExpression(name, RemoteState.Domain.User) {
        componentWidth()
      }
    if (materialized) {
      RemoteText(text = width.toRemoteString(DecimalFormat("0")), color = Color.Transparent.rc)
    }
    return width
  }

  private fun observe(probe: String, bytes: ByteArray) {
    compose.runOnIdle {
      for (w in listOf(100, 300)) {
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
        for (frame in 1..4) {
          player.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(50, View.MeasureSpec.EXACTLY),
          )
          player.layout(0, 0, w, 50)
          val image = Bitmap.createBitmap(w, 50, Bitmap.Config.ARGB_8888)
          player.draw(Canvas(image))
          val top = image.getPixel(5, 5) == android.graphics.Color.rgb(183, 28, 28)
          val bottom = image.getPixel(5, 45) == android.graphics.Color.rgb(21, 101, 192)
          println("STATE_LAYOUT $probe $w frame=$frame top=$top bottom=$bottom")
          image.recycle()
        }
      }
    }
  }

  @Test
  fun intOverload() =
    observe(
      "intOverload",
      document {
        val wide = width("__probe_int", materialized = true).isGreaterThanOrEqualTo(200f.rf)
        RemoteStateLayout(wide.select(1.ri, 0.ri), 0, 1) { index: Int -> Branch(index == 1) }
      },
    )

  @Test
  fun intOverloadBare() =
    observe(
      "intOverloadBare",
      document {
        val wide = width("__probe_int_bare", materialized = false).isGreaterThanOrEqualTo(200f.rf)
        RemoteStateLayout(wide.select(1.ri, 0.ri), 0, 1) { index: Int -> Branch(index == 1) }
      },
    )

  @Test
  fun transitive() =
    observe(
      "transitive",
      document {
        val wide = width("__probe_transitive", materialized = false).isGreaterThanOrEqualTo(200f.rf)
        RemoteStateLayout(wide) { on -> Branch(on) }
      },
    )

  @Test
  fun overlay() =
    observe(
      "overlay",
      document {
        val wide = width("__probe_overlay", materialized = true).isGreaterThanOrEqualTo(200f.rf)
        RemoteStateLayout(wide) { on -> Branch(on) }
      },
    )
}
