package ee.schimke.terrazzo.wear.widget

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WidgetInstanceId
import androidx.wear.compose.remote.material3.RemoteColorScheme
import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.edit.validateFor
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.HaRcComposePlayer
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * One Wear widget template, captured with named container values and the app's profile, made into
 * each widget's document on the watch: the same header Glance Wear writes, playable as a Wear
 * widget, and drawn as Glance Wear's own capture for those params draws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearWidgetTemplateTest {
  @get:Rule val compose = createComposeRule()

  private val context = RuntimeEnvironment.getApplication()
  private val registry = defaultRegistry()
  private val colors = RemoteColorScheme(terrazzoWearColorScheme(ThemeStyle.entries.first()))

  private val card =
    CardConfig(
      type = "tile",
      raw =
        buildJsonObject {
          put("type", "tile")
          put("entity", "light.kitchen")
        },
    )
  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          "light.kitchen" to
            EntityState(
              "light.kitchen",
              "on",
              JsonObject(mapOf("friendly_name" to JsonPrimitive("Kitchen"))),
            )
        )
    )

  private fun params(type: Int, height: Float, horizontal: Float, vertical: Float, corner: Float) =
    WearWidgetParams(
      instanceId = WidgetInstanceId("widgets", 1),
      containerType = type,
      widthDp = 200f,
      heightDp = height,
      horizontalPaddingDp = horizontal,
      verticalPaddingDp = vertical,
      cornerRadiusDp = corner,
    )

  private val containers =
    listOf(
      "small" to params(ContainerInfo.CONTAINER_TYPE_SMALL, 60f, 8f, 8f, 26f),
      "large" to params(ContainerInfo.CONTAINER_TYPE_LARGE, 112f, 8f, 8f, 26f),
      "wide-insets" to params(ContainerInfo.CONTAINER_TYPE_LARGE, 112f, 24f, 8f, 26f),
      "tall-insets" to params(ContainerInfo.CONTAINER_TYPE_LARGE, 112f, 8f, 20f, 26f),
      "square" to params(ContainerInfo.CONTAINER_TYPE_LARGE, 112f, 8f, 8f, 4f),
    )

  private val template = runBlocking {
    WearWidgetTemplate.capture(context) {
      SlotContent(registry, colors, card, snapshot, template = false, HaUberTheme.Classic)
    }
  }

  @Test
  fun eachWidgetGetsGlanceWearsHeaderAndAPlayableDocumentWithItsContainerValues() {
    for ((name, params) in containers) {
      val widget =
        assertNotNull(
          runBlocking { WearWidgetTemplate.instantiate(context, template, params) },
          name,
        )
      val glance = runBlocking {
        slotDirectDocument(registry, colors, card, snapshot)
          .captureRawContent(context, params, false)
          .rcDocument
      }
      val document = RcDocumentCodec.decode(widget.rcDocument, RcWireLimits())
      assertEquals(RcDocumentCodec.decode(glance, RcWireLimits()).header, document.header, name)
      assertEquals(emptyList(), document.validateFor(RcTargetProfiles.WEAR_WIDGETS), name)
      assertTrue(
        document.namedVariables.none { it.name.contains("wear.container.") },
        "$name: the container's values are hardcoded",
      )
    }
  }

  @Test
  fun aContainerTintIsWrittenIntoTheWidgetsDocument() {
    val tint = 0xFF5A3A2E.toInt()
    val (_, params) = containers.first()
    val widget =
      assertNotNull(
        runBlocking { WearWidgetTemplate.instantiate(context, template, params, false, tint) }
      )
    val untinted =
      assertNotNull(runBlocking { WearWidgetTemplate.instantiate(context, template, params) })
    fun holds(bytes: ByteArray, argb: Int): Boolean {
      val word =
        byteArrayOf(
          (argb ushr 24).toByte(),
          (argb ushr 16).toByte(),
          (argb ushr 8).toByte(),
          argb.toByte(),
        )
      return (0..bytes.size - 4).any { i -> (0 until 4).all { bytes[i + it] == word[it] } }
    }
    assertTrue(holds(widget.rcDocument, tint), "the tint fills the container")
    assertTrue(!holds(untinted.rcDocument, tint), "an untinted widget keeps the default surface")
  }

  @Test
  fun eachWidgetDrawsAsGlanceWearsOwnCaptureForItsParams() {
    var bytes by mutableStateOf(ByteArray(0))
    var size by mutableStateOf(200f to 60f)
    compose.setContent {
      Box(Modifier.size(size.first.dp, size.second.dp).testTag("widget")) {
        key(bytes) {
          if (bytes.isNotEmpty()) {
            HaRcComposePlayer(
              documentBytes = bytes,
              modifier = Modifier.size(size.first.dp, size.second.dp),
              fallback = { error("a Wear widget document must play") },
            )
          }
        }
      }
    }
    for ((name, params) in containers) {
      val glance = runBlocking {
        slotDirectDocument(registry, colors, card, snapshot)
          .captureRawContent(context, params, false)
          .rcDocument
      }
      val widget = runBlocking { WearWidgetTemplate.instantiate(context, template, params) }!!
      compose.runOnIdle {
        size = params.widthDp to params.heightDp
        bytes = glance
      }
      compose.waitForIdle()
      val expected = pixels("template-$name-glance")
      compose.runOnIdle { bytes = widget.rcDocument }
      compose.waitForIdle()
      val actual = pixels("template-$name-neutral")
      var differing = 0
      for (y in 0 until expected.height) for (x in 0 until expected.width) {
        if (expected[x, y] != actual[x, y]) differing++
      }
      // Text rewritten from CORE_TEXT may antialias a few edge pixels differently.
      val total = expected.width * expected.height
      assertTrue(differing * 100 < total, "$name: $differing of $total pixels differ")
    }
  }

  private fun pixels(name: String) =
    compose
      .onNodeWithTag("widget")
      .captureToImage()
      .also { image ->
        val dir = File("build/wear-templates").also { it.mkdirs() }
        dir.resolve("$name.png").outputStream().use {
          image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
      }
      .toPixelMap()
}
