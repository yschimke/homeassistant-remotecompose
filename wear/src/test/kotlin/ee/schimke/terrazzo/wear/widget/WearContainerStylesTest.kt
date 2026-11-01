package ee.schimke.terrazzo.wear.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.offset
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
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
import ee.schimke.composeai.rcplayer.edit.validateFor
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.ContainerTint
import ee.schimke.ha.rc.HaRcComposePlayer
import ee.schimke.ha.rc.LauncherDesigns
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.converterFor
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every card style's Wear container, as the watch makes a widget of it: a battery sensor's tile
 * captured as the style's neutral template ([neutralWidgetTemplate]'s capture), its level and all,
 * made into each container's widget document and baked with the card's values. Each document must
 * play in the Wear widget profile; each is rendered to `build/wear-container-styles/` for review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearContainerStylesTest {
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
          put("entity", "sensor.phone_battery")
        },
    )

  private fun snapshot(level: String) =
    HaSnapshot(
      states =
        mapOf(
          "sensor.phone_battery" to
            EntityState(
              "sensor.phone_battery",
              level,
              buildJsonObject {
                put("friendly_name", "Phone")
                put("device_class", "battery")
                put("unit_of_measurement", "%")
              },
            )
        )
    )

  private fun params(type: Int, height: Float, corner: Float) =
    WearWidgetParams(
      instanceId = WidgetInstanceId("widgets", 1),
      containerType = type,
      widthDp = 200f,
      heightDp = height,
      horizontalPaddingDp = 8f,
      verticalPaddingDp = 8f,
      cornerRadiusDp = corner,
    )

  private val containers =
    listOf(
      "round" to params(ContainerInfo.CONTAINER_TYPE_LARGE, 112f, 26f),
      "square" to params(ContainerInfo.CONTAINER_TYPE_LARGE, 112f, 4f),
      "small" to params(ContainerInfo.CONTAINER_TYPE_SMALL, 60f, 26f),
    )

  @Test
  fun oversizedContentIsClippedToTheHostsRoundedFrame() {
    val params = params(ContainerInfo.CONTAINER_TYPE_SMALL, 60f, 26f)
    val template = runBlocking {
      WearWidgetTemplate.capture(context) {
        RemoteBox(
          RemoteModifier.offset((-8).rdp, (-8).rdp).size(400.rdp, 400.rdp).background(Color.Red.rc)
        ) {}
      }
    }
    val widget =
      assertNotNull(runBlocking { WearWidgetTemplate.instantiate(context, template, params) })
    assertEquals(
      emptyList(),
      RcDocumentCodec.decode(widget.rcDocument, RcWireLimits())
        .validateFor(RcTargetProfiles.WEAR_WIDGETS),
    )
    compose.setContent {
      Box(Modifier.size(216.dp, 76.dp).background(Color.Magenta).testTag("widget")) {
        HaRcComposePlayer(
          widget.rcDocument,
          Modifier.size(216.dp, 76.dp),
          fallback = { error("must play") },
        )
      }
    }
    val bitmap = compose.onNodeWithTag("widget").captureToImage().asAndroidBitmap()
    assertEquals(android.graphics.Color.MAGENTA, bitmap.getPixel(0, 0))
    assertEquals(android.graphics.Color.RED, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))

    // Exercise the AndroidX View player too: the opcode being allowed by the profile alone does
    // not prove this renderer clips the content.
    val document =
      CoreDocument().apply {
        initFromBuffer(RemoteComposeBuffer.fromInputStream(widget.rcDocument.inputStream()))
      }
    val player = RemoteComposePlayer(context).apply { setDocument(RemoteDocument(document)) }
    val width = 216
    val height = 76
    player.measure(
      View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
    )
    player.layout(0, 0, width, height)
    val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    frame.eraseColor(android.graphics.Color.MAGENTA)
    player.draw(Canvas(frame))
    assertEquals(android.graphics.Color.MAGENTA, frame.getPixel(0, 0))
    assertEquals(android.graphics.Color.RED, frame.getPixel(width / 2, height / 2))
  }

  @Test
  fun everyStylesContainerPlaysAsAWearWidgetInEveryContainer() {
    var bytes by mutableStateOf(ByteArray(0))
    var height by mutableStateOf(112f)
    compose.setContent {
      Box(Modifier.size(200.dp, height.dp).testTag("widget")) {
        key(bytes) {
          if (bytes.isNotEmpty()) {
            HaRcComposePlayer(
              documentBytes = bytes,
              modifier = Modifier.size(200.dp, height.dp),
              fallback = { error("a Wear widget document must play") },
            )
          }
        }
      }
    }
    val dir = File("build/wear-container-styles").also { it.mkdirs() }
    val live = snapshot("62")
    val design = CardDesignTemplates.designCard(card, live, registry)
    val converter = registry.converterFor(design.type)!!
    val properties = assertNotNull(converter.wearDesignProperties(design, live))
    val input = assertNotNull(converter.wearDesignTemplate(properties))
    val level = assertNotNull(wearLevel(input.card, input.snapshot), "a battery tile is a level")
    for (style in HaUberTheme.all) {
      val template = runBlocking {
        WearWidgetTemplate.capture(context, style, level) {
          SlotContent(registry, colors, input.card, input.snapshot, template = true, style)
        }
      }
      for ((name, params) in containers) {
        val fill = ContainerTint.argbFor(card, live, style)
        val widget =
          assertNotNull(
            runBlocking { WearWidgetTemplate.instantiate(context, template, params, false, fill) },
            "${style.id} $name",
          )
        val playback = CardDesignTemplates.playback(design, widget.rcDocument, registry)
        val baked =
          assertNotNull(
            LauncherDesigns.bakeValues(widget.rcDocument, playback, live),
            "${style.id} $name: bakes",
          )
        assertEquals(
          emptyList(),
          RcDocumentCodec.decode(baked, RcWireLimits()).validateFor(RcTargetProfiles.WEAR_WIDGETS),
          "${style.id} $name",
        )
        compose.runOnIdle {
          height = params.heightDp
          bytes = baked
        }
        compose.waitForIdle()
        val image = compose.onNodeWithTag("widget").captureToImage().asAndroidBitmap()
        dir.resolve("${style.id}-$name.png").outputStream().use {
          image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
      }
    }
  }
}
