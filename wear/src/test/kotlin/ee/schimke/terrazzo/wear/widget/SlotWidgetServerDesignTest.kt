package ee.schimke.terrazzo.wear.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WidgetInstanceId
import androidx.wear.compose.remote.material3.RemoteColorScheme
import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.validateFor
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.HaRcComposePlayer
import ee.schimke.ha.rc.LauncherDesigns
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.converterFor
import ee.schimke.ha.rc.framedDesignProperties
import ee.schimke.ha.rc.framedDesignTemplate
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * A slot whose card has a design server Wear design (synced by the phone) bakes that design for its
 * container, Small or Large, instead of capturing the card's own; without one, or with one that
 * can't be baked, it draws as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SlotWidgetServerDesignTest {
  @get:Rule val compose = createComposeRule()

  private val registry = defaultRegistry()
  private val style = ThemeStyle.entries.first()
  private val colors = RemoteColorScheme(terrazzoWearColorScheme(style))

  private fun params(containerType: Int, heightDp: Float) =
    WearWidgetParams(
      instanceId = WidgetInstanceId("widgets", 1),
      containerType = containerType,
      widthDp = 200f,
      heightDp = heightDp,
      verticalPaddingDp = 8f,
      horizontalPaddingDp = 8f,
      cornerRadiusDp = 26f,
    )

  private val small = params(ContainerInfo.CONTAINER_TYPE_SMALL, 60f)
  private val large = params(ContainerInfo.CONTAINER_TYPE_LARGE, 112f)

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

  /**
   * A Wear design for [card] standing in for the server's: its template in another card style, so a
   * widget baked from it differs from one baked from the card's own.
   */
  private fun serverDesign(params: WearWidgetParams, cardStyle: HaUberTheme): ByteArray {
    val design = CardDesignTemplates.designCard(card, snapshot, registry)
    val converter = requireNotNull(registry.converterFor(design.type))
    val input =
      requireNotNull(
        converter.framedDesignTemplate(
          requireNotNull(converter.framedDesignProperties(design, snapshot))
        )
      )
    return runBlocking {
      slotDocument(
          registry,
          colors,
          input.card,
          input.snapshot,
          0,
          template = true,
          cardStyle = cardStyle,
        )
        .captureRawContent(RuntimeEnvironment.getApplication(), params, false)
        .rcDocument
    }
  }

  private fun capture(
    params: WearWidgetParams,
    cardStyle: HaUberTheme = HaUberTheme.Classic,
    wearDesign: (String) -> ByteArray? = { null },
  ): ByteArray = runBlocking {
    slotWidgetData(
        registry,
        colors,
        style,
        card,
        snapshot,
        0,
        cardStyle,
        wearDesign = wearDesign,
      )
      .captureRawContent(RuntimeEnvironment.getApplication(), params, false)
      .rcDocument
  }

  private val lcd = HaUberTheme.byId("lcd")

  @Test
  fun eachContainerBakesTheServersDesignForIt() {
    for ((params, container) in
      listOf(small to DesignKey.WEAR_SMALL, large to DesignKey.WEAR_LARGE)) {
      val design = serverDesign(params, lcd)
      // A Wear design fixture for the design server's tests.
      File("build/wear-designs")
        .apply { mkdirs() }
        .resolve("tile-light-$container.rc")
        .writeBytes(design)
      val asked = mutableListOf<String>()
      val baked =
        capture(params) {
          asked += it
          design.takeIf { _ -> it == container }
        }
      assertEquals(listOf(container), asked)
      val own = capture(params)
      assertFalse(baked.contentEquals(own), "$container: the server's design, not the card's own")
      // The same template the card's own capture would bake in that card style.
      assertContentEquals(capture(params, cardStyle = lcd), baked)
      assertTrue("Kitchen" in String(baked, Charsets.ISO_8859_1), "the values are baked in")
      assertEquals(
        emptyList(),
        RcDocumentCodec.decode(baked, RcWireLimits()).validateFor(RcTargetProfiles.WEAR_WIDGETS),
      )
    }
  }

  /** Samsung's round host's Large container: a stadium, narrower and taller than the squircle. */
  private val roundLarge =
    WearWidgetParams(
      instanceId = WidgetInstanceId("widgets", 1),
      containerType = ContainerInfo.CONTAINER_TYPE_LARGE,
      widthDp = 160f,
      heightDp = 136f,
      verticalPaddingDp = 16f,
      horizontalPaddingDp = 35f,
      cornerRadiusDp = 999f,
    )

  /** [serverDesign], exported with `hostFrame: device`: its host frame named, not baked. */
  private fun hostFramedDesign(params: WearWidgetParams): ByteArray {
    val design = CardDesignTemplates.designCard(card, snapshot, registry)
    val converter = requireNotNull(registry.converterFor(design.type))
    val input =
      requireNotNull(
        converter.framedDesignTemplate(
          requireNotNull(converter.framedDesignProperties(design, snapshot))
        )
      )
    return runBlocking {
      HostFramedWearWidgetDocument(
          WearWidgetBrush,
          slotContent(registry, colors, input.card, input.snapshot, 0, template = true),
        )
        .captureRawContent(RuntimeEnvironment.getApplication(), params, false)
        .rcDocument
    }
  }

  @Test
  fun theWatchNamesTheHostFrameTheExportDeclares() {
    assertEquals(
      HostFramedWearWidgetDocument.HostFrame.valuesOf(roundLarge),
      WearHostFrame.valuesOf(roundLarge),
    )
  }

  @Test
  fun aDesignThatNamesItsHostFrameTakesTheWatchesHost() {
    // One design, captured for the squircle (as an export is), played on Samsung's round host.
    val squircleCapture = hostFramedDesign(large)
    val roundCapture = hostFramedDesign(roundLarge)
    val onRound = capture(roundLarge) { squircleCapture.takeIf { _ -> it == DesignKey.WEAR_LARGE } }
    val roundOwn = capture(roundLarge) { roundCapture.takeIf { _ -> it == DesignKey.WEAR_LARGE } }
    // The same bake without the watch's frame: what this design played as before.
    val notFramed =
      LauncherDesigns.bakeValues(
        squircleCapture,
        CardDesignTemplates.playback(
          CardDesignTemplates.designCard(card, snapshot, registry),
          squircleCapture,
          registry,
        ),
        snapshot,
      )!!
    var shown by mutableStateOf(onRound)
    compose.setContent {
      val frame =
        Modifier.size(
          (roundLarge.widthDp + 2 * roundLarge.horizontalPaddingDp).dp,
          (roundLarge.heightDp + 2 * roundLarge.verticalPaddingDp).dp,
        )
      Box(frame.testTag("widget")) {
        key(shown) {
          HaRcComposePlayer(
            documentBytes = shown,
            modifier = frame,
            fallback = { error("a baked Wear design must play") },
          )
        }
      }
    }
    fun pixels(bytes: ByteArray): PixelMap {
      compose.runOnIdle { shown = bytes }
      compose.waitForIdle()
      return compose.onNodeWithTag("widget").captureToImage().toPixelMap()
    }
    fun differing(a: PixelMap, b: PixelMap): Int {
      var count = 0
      for (y in 0 until a.height) for (x in 0 until a.width) if (a[x, y] != b[x, y]) count++
      return count
    }
    val expected = pixels(roundOwn)
    // The watch's own frame written in: the squircle capture plays as the round host's own does.
    assertEquals(0, differing(expected, pixels(onRound)), "takes the round host's frame")
    // Without it, the squircle's 8dp insets and 26dp corners stay.
    assertTrue(differing(expected, pixels(notFramed)) > 0, "the captured frame differs")
  }

  @Test
  fun aDesignThatCannotBeBakedFallsBackToTheCardsOwn() {
    val own = capture(large)
    assertContentEquals(own, capture(large) { byteArrayOf(1, 2, 3) })
    assertContentEquals(own, capture(large) { null })
  }
}
