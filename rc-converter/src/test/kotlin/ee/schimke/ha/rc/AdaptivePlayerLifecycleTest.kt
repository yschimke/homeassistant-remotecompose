@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Stable Compose call sites, with real AndroidView attachment and native pixel assertions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptivePlayerLifecycleTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val red = Color(0xFFB71C1C)
  private val blue = Color(0xFF1565C0)

  private fun document(
    color: Color,
    height: Int? = 40,
    profile: Profile = androidXExperimentalWrap,
  ): ByteArray = runBlocking {
    captureSingleRemoteDocument(
        context = compose.activity,
        creationDisplayInfo = RemoteCreationDisplayInfo(300, 400, 160),
        profile = profile,
      ) {
        RemoteBox(
          modifier =
            (if (height == null) RemoteModifier.fillMaxSize()
              else RemoteModifier.fillMaxWidth().height(height.rdp))
              .background(color.rc),
          contentAlignment = RemoteAlignment.BottomEnd,
        ) {
          if (height == null) RemoteBox(RemoteModifier.size(12.rdp).background(Color.White.rc))
        }
      }
      .bytes
  }

  private fun players(view: View): List<RemoteComposePlayer> =
    when (view) {
      is RemoteComposePlayer -> listOf(view)
      is ViewGroup -> (0 until view.childCount).flatMap { players(view.getChildAt(it)) }
      else -> emptyList()
    }

  private fun frame(name: String, color: Color, width: Int, height: Int, marker: Boolean = false) {
    compose.waitForIdle()
    compose.runOnIdle {
      val player = players(compose.activity.window.decorView).single()
      val image = Bitmap.createBitmap(player.width, player.height, Bitmap.Config.ARGB_8888)
      player.draw(Canvas(image))
      save(name, image)
      assertEquals(width, player.width, "$name width")
      assertEquals(height, player.height, "$name height")
      val expected =
        android.graphics.Color.rgb(
          (color.red * 255).toInt(),
          (color.green * 255).toInt(),
          (color.blue * 255).toInt(),
        )
      for ((x, y) in listOf(2 to 2, width / 2 to height / 2, width - 20 to height - 20)) {
        assertEquals(expected, image.getPixel(x, y), "$name pixel ($x,$y)")
      }
      if (marker)
        assertEquals(
          android.graphics.Color.WHITE,
          image.getPixel(width - 3, height - 3),
          "$name bottom-end marker must reflow",
        )
      image.recycle()
    }
  }

  private fun save(name: String, image: Bitmap) {
    val dir = File("build/adaptive-player-evidence").also { it.mkdirs() }
    dir.resolve("$name.png").outputStream().use {
      image.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }

  @Test fun fixedSlotReplacesBytesWithoutCallerKey() = replacement(fixed = true)

  @Test fun wrapSlotReplacesBytesAndIntrinsicHeightWithoutCallerKey() = replacement(fixed = false)

  private fun replacement(fixed: Boolean) {
    val first = document(red)
    var bytes by mutableStateOf(first)
    val next = document(blue, 70)
    val prefix = if (fixed) "fixed-replacement" else "wrap-replacement"
    compose.setContent {
      WrapAdaptiveRemoteDocumentPlayer(
        bytes,
        if (fixed) Modifier.size(180.dp, 30.dp) else Modifier.width(180.dp),
      )
    }
    frame("$prefix-before", red, 180, if (fixed) 30 else 40)
    compose.runOnIdle { bytes = next }
    frame("$prefix-after", blue, 180, if (fixed) 30 else 70)
    compose.runOnIdle { bytes = first }
    frame("$prefix-restored", red, 180, if (fixed) 30 else 40)
  }

  @Test fun fixedSlotGrowsAndShrinksAfterFirstDraw() = resize(fixed = true)

  @Test fun wrapSlotGrowsAndShrinksAfterFirstDraw() = resize(fixed = false)

  @Test
  fun fixedPaintMeasureSlotGrowsAndShrinksAfterFirstDraw() =
    resize(fixed = true, profile = androidXExperimental)

  private fun resize(fixed: Boolean, profile: Profile = androidXExperimentalWrap) {
    var bytes by mutableStateOf(document(blue, if (fixed) null else 70, profile))
    val replacement = document(red, if (fixed) null else 70, profile)
    val original = bytes
    var color = blue
    var dimensions by mutableStateOf(180 to 90)
    compose.setContent {
      Box {
        val (w, h) = dimensions
        WrapAdaptiveRemoteDocumentPlayer(
          bytes,
          if (fixed) Modifier.size(w.dp, h.dp) else Modifier.width(w.dp).heightIn(max = h.dp),
        )
      }
    }
    for ((w, h) in listOf(180 to 90, 280 to 140, 100 to 30, 180 to 90)) {
      compose.runOnIdle { dimensions = w to h }
      val name =
        "${if (fixed) "fixed" else "wrap"}-${if (profile === androidXExperimental) "paint" else "measure"}-resize-${w}x$h"
      frame(
        name,
        color,
        w,
        if (fixed) h else minOf(70, h),
        marker = fixed,
      )
      color = if (color == blue) red else blue
      compose.runOnIdle { bytes = if (color == red) replacement else original }
      frame("$name-replaced", color, w, if (fixed) h else minOf(70, h), marker = fixed)
    }
  }

  @Test
  fun intrinsicDocumentInNaturalSlackAndTightFixedSlots() {
    val bytes = document(blue)
    var height by mutableStateOf(40)
    compose.setContent { WrapAdaptiveRemoteDocumentPlayer(bytes, Modifier.size(180.dp, height.dp)) }
    for (h in listOf(40, 80, 20)) {
      compose.runOnIdle { height = h }
      compose.waitForIdle()
      compose.runOnIdle {
        val player = players(compose.activity.window.decorView).single()
        assertEquals(180, player.width)
        assertEquals(h, player.height)
        val image = Bitmap.createBitmap(180, h, Bitmap.Config.ARGB_8888)
        player.draw(Canvas(image))
        save("intrinsic-fixed-$h", image)
        assertEquals(android.graphics.Color.rgb(21, 101, 192), image.getPixel(5, 5))
        if (h > 40) assertEquals(android.graphics.Color.TRANSPARENT, image.getPixel(5, h - 5))
        image.recycle()
      }
    }
  }

  /** #153 observation through AndroidX's public Compose host; no adaptive-host workaround. */
  @OptIn(androidx.compose.remote.player.compose.ExperimentalRemotePlayerApi::class)
  @Test
  fun upstreamWidthOnlyIntrinsicHeightEvidence() {
    val bytes = document(blue)
    val core =
      CoreDocument().apply {
        initFromBuffer(RemoteComposeBuffer.fromInputStream(bytes.inputStream()))
      }
    val previous =
      androidx.compose.remote.player.compose.RemoteComposePlayerFlags.shouldPlayerWrapContentSize
    androidx.compose.remote.player.compose.RemoteComposePlayerFlags.shouldPlayerWrapContentSize =
      true
    try {
      compose.setContent {
        androidx.compose.remote.player.compose.RemoteDocumentPlayer(
          document = core,
          documentWidth = 300,
          documentHeight = 400,
          modifier = Modifier.width(180.dp),
        )
      }
      compose.waitForIdle()
      compose.runOnIdle {
        val player = players(compose.activity.window.decorView).single()
        val image = Bitmap.createBitmap(player.width, player.height, Bitmap.Config.ARGB_8888)
        player.draw(Canvas(image))
        save("upstream-width-only", image)
        println("RAW_INTRINSIC width=${player.width} height=${player.height} expectedHeight=40")
        image.recycle()
      }
    } finally {
      androidx.compose.remote.player.compose.RemoteComposePlayerFlags.shouldPlayerWrapContentSize =
        previous
    }
  }

  /** Independent upstream reproduction: reuse one raw View, without our host warmup/swap. */
  @Test
  fun rawPlayerResizeEvidenceForBothPaintMeasureProfiles() {
    for ((label, profile) in
      listOf("paint" to androidXExperimental, "wrap" to androidXExperimentalWrap)) {
      val bytes = document(blue, null, profile)
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
        for ((w, h) in listOf(180 to 90, 280 to 140, 100 to 30)) {
          player.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
          )
          player.layout(0, 0, w, h)
          val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
          player.draw(Canvas(image))
          save("upstream-$label-${w}x$h", image)
          println(
            "RAW_RESIZE $label ${w}x$h corner=${image.getPixel(w - 3, h - 3).toUInt().toString(16)} center=${image.getPixel(w / 2, h / 2).toUInt().toString(16)}"
          )
          assertTrue(player.measuredWidth == w && player.measuredHeight == h)
          image.recycle()
        }
      }
    }
  }
}
