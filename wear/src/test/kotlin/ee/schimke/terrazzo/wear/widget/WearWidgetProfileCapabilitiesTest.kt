@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.wear.widget

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.drawWithContent
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.shaders.RemoteBrush
import androidx.compose.remote.creation.compose.shaders.horizontalGradient
import androidx.compose.remote.creation.compose.shaders.linearGradient
import androidx.compose.remote.creation.compose.shaders.radialGradient
import androidx.compose.remote.creation.compose.shaders.sweepGradient
import androidx.compose.remote.creation.compose.shaders.verticalGradient
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteDp
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.clamp
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WidgetInstanceId
import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.edit.validateFor
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.rc.HaRcComposePlayer
import ee.schimke.ha.rc.components.animateRemoteFloat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a Wear widget container can draw: one small document per drawing feature, captured as a Wear
 * template, checked against the Wear widget profile and made into a widget
 * ([WearWidgetTemplate.instantiate], which runs `adaptTo`), then played. Every feature here plays;
 * the renders go to `build/wear-profile-capabilities/` and the table to
 * `build/wear-profile-capabilities.md` (see `docs/architecture/wear-container-design.md`).
 *
 * A dash path effect validates and plays too, but the player draws it solid, so a dashed stroke is
 * not one of the container's tools.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearWidgetProfileCapabilitiesTest {
  @get:Rule val compose = createComposeRule()
  private val context = RuntimeEnvironment.getApplication()
  private val params =
    WearWidgetParams(
      instanceId = WidgetInstanceId("widgets", 1),
      containerType = ContainerInfo.CONTAINER_TYPE_LARGE,
      widthDp = 200f,
      heightDp = 112f,
      horizontalPaddingDp = 8f,
      verticalPaddingDp = 8f,
      cornerRadiusDp = 26f,
    )

  private val colors = listOf(Color.Red.rc, Color.Blue.rc)

  private fun level(): RemoteFloat =
    RemoteFloat.createNamedRemoteFloat("sensor.x.numeric_state", 40f, RemoteState.Domain.User)

  private val probes: List<Pair<String, @Composable () -> Unit>> =
    listOf(
      "vertical gradient bg" to
        {
          RemoteBox(RemoteModifier.fillMaxSize().background(RemoteBrush.verticalGradient(colors)))
        },
      "horizontal gradient bg" to
        {
          RemoteBox(RemoteModifier.fillMaxSize().background(RemoteBrush.horizontalGradient(colors)))
        },
      "linear (diagonal) gradient" to
        {
          RemoteBox(
            RemoteModifier.fillMaxSize()
              .background(
                RemoteBrush.linearGradient(
                  colors,
                  RemoteOffset(0f.rf, 0f.rf),
                  RemoteOffset(80f.rf, 80f.rf),
                )
              )
          )
        },
      "radial gradient bg" to
        {
          RemoteBox(RemoteModifier.fillMaxSize().background(RemoteBrush.radialGradient(colors)))
        },
      "sweep gradient bg" to
        {
          RemoteBox(RemoteModifier.fillMaxSize().background(RemoteBrush.sweepGradient(colors)))
        },
      "border modifier (rounded)" to
        {
          RemoteBox(
            RemoteModifier.fillMaxSize()
              .border(2.rdp, Color.Red.rc, RemoteRoundedCornerShape(12.rdp))
          )
        },
      "clip to named radius" to
        {
          val corner = RemoteDp.createNamedRemoteDp("probe.corner", 26f, RemoteState.Domain.User)
          RemoteBox(
            RemoteModifier.fillMaxSize()
              .clip(RemoteRoundedCornerShape(corner))
              .background(Color.Red.rc)
          )
        },
      "stroke round rect" to
        {
          RemoteBox(
            RemoteModifier.fillMaxSize().drawWithContent {
              drawRoundRect(
                RemotePaint {
                  style = PaintingStyle.Stroke
                  strokeWidth = 4f.rf
                  color = Color.Red.rc
                },
                cornerRadius = RemoteOffset(20f.rf, 20f.rf),
              )
            }
          )
        },
      "gradient stroke (shader on stroke)" to
        {
          RemoteBox(
            RemoteModifier.fillMaxSize().drawWithContent {
              val paint = RemotePaint {
                style = PaintingStyle.Stroke
                strokeWidth = 4f.rf
              }
              with(RemoteBrush.sweepGradient(colors)) { applyTo(paint, size) }
              drawRoundRect(paint, cornerRadius = RemoteOffset(20f.rf, 20f.rf))
            }
          )
        },
      "dashed stroke" to
        {
          RemoteBox(
            RemoteModifier.fillMaxSize().drawWithContent {
              drawRoundRect(
                RemotePaint {
                  style = PaintingStyle.Stroke
                  strokeWidth = 3f.rf
                  color = Color.Red.rc
                  pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                },
                cornerRadius = RemoteOffset(20f.rf, 20f.rf),
              )
            }
          )
        },
      "rect width from named float" to
        {
          RemoteBox(
            RemoteModifier.fillMaxSize().drawWithContent {
              val fraction = clamp(level() / 100f.rf, 0f, 1f)
              drawRect(
                RemotePaint { color = Color.Red.rc },
                size = RemoteSize(size.width * fraction, size.height),
              )
            }
          )
        },
      "animated named float" to
        {
          val animated = animateRemoteFloat(level() / 100f.rf, 0.6f)
          RemoteBox(
            RemoteModifier.fillMaxSize().drawWithContent {
              drawRect(
                RemotePaint { color = Color.Red.rc },
                size = RemoteSize(size.width * animated, size.height),
              )
            }
          )
        },
      "arc" to
        {
          RemoteBox(
            RemoteModifier.fillMaxSize().drawWithContent {
              drawArc(
                RemotePaint {
                  style = PaintingStyle.Stroke
                  strokeWidth = 6f.rf
                  color = Color.Red.rc
                },
                0f.rf,
                270f.rf,
                false,
                RemoteOffset(0f.rf, 0f.rf),
                size,
              )
            }
          )
        },
      "line" to
        {
          RemoteBox(
            RemoteModifier.fillMaxSize().drawWithContent {
              drawLine(
                RemotePaint {
                  strokeWidth = 2f.rf
                  color = Color.Red.rc
                },
                RemoteOffset(0f.rf, 0f.rf),
                RemoteOffset(size.width, size.height),
              )
            }
          )
        },
      "clipRect" to
        {
          RemoteBox(
            RemoteModifier.fillMaxSize().drawWithContent {
              clipRect(0f.rf, 0f.rf, size.width * 0.5f, size.height) {
                drawRoundRect(
                  RemotePaint { color = Color.Red.rc },
                  cornerRadius = RemoteOffset(20f.rf, 20f.rf),
                )
              }
            }
          )
        },
      "alpha color + translucent layers" to
        {
          RemoteBox(
            RemoteModifier.fillMaxSize()
              .background(Color.Red.copy(alpha = 0.3f).rc)
              .background(Color.Blue.copy(alpha = 0.3f).rc)
          )
        },
    )

  @Test
  fun aNameUsedTwiceIsOneNamedVariable() {
    val template = runBlocking {
      WearWidgetTemplate.capture(context) {
        RemoteBox(
          RemoteModifier.fillMaxSize().drawWithContent {
            drawRect(
              RemotePaint { color = Color.Red.rc },
              size = RemoteSize(size.width * level() / 100f.rf, size.height),
            )
            drawRect(
              RemotePaint { color = Color.Blue.rc },
              size = RemoteSize(size.width, size.height * level() / 100f.rf),
            )
          }
        )
      }
    }
    val named =
      RcDocumentCodec.decode(template, RcWireLimits()).namedVariables.filter {
        "sensor.x" in it.name
      }
    // So a container's level and a card's meter glyph share one `<slot>.numeric_state`.
    assertEquals(1, named.size)
  }

  @Test
  fun everyFeaturePlaysAsAWearWidget() {
    var bytes by mutableStateOf(ByteArray(0))
    compose.setContent {
      Box(Modifier.size(200.dp, 112.dp).testTag("widget")) {
        key(bytes) {
          if (bytes.isNotEmpty())
            HaRcComposePlayer(
              documentBytes = bytes,
              modifier = Modifier.size(200.dp, 112.dp),
              fallback = {},
            )
        }
      }
    }
    val dir = File("build/wear-profile-capabilities").also { it.mkdirs() }
    val report =
      StringBuilder(
        "| Feature | validateFor violations | playable after adaptTo |\n|---|---|---|\n"
      )
    for ((name, content) in probes) {
      val result = runCatching {
        val template = runBlocking { WearWidgetTemplate.capture(context) { content() } }
        val violations =
          RcDocumentCodec.decode(template, RcWireLimits())
            .validateFor(RcTargetProfiles.WEAR_WIDGETS)
        val widget = runBlocking { WearWidgetTemplate.instantiate(context, template, params) }
        val playable = widget != null
        if (widget != null) {
          compose.runOnIdle { bytes = widget.rcDocument }
          compose.waitForIdle()
          val image = compose.onNodeWithTag("widget").captureToImage().asAndroidBitmap()
          dir.resolve(name.replace(Regex("[^a-z]+"), "-") + ".png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
          }
        }
        "| $name | ${violations.joinToString("; ").ifEmpty { "none" }} | $playable |"
      }
      report.append(
        result.getOrElse { "| $name | capture failed: ${it.message?.take(120)} | false |" }
      )
      report.append('\n')
    }
    File("build/wear-profile-capabilities.md").writeText(report.toString())
    val failing =
      report.lines().drop(2).filter { it.isNotBlank() && !it.endsWith("| none | true |") }
    assertEquals(emptyList(), failing)
  }
}
