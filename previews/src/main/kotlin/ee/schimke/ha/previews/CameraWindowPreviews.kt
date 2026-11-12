@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize as uiFillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaSpace
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.LocalPictureImageStrategy
import ee.schimke.ha.rc.components.PictureImageStrategy
import ee.schimke.ha.rc.components.ProvideHaUberTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Rugged Digital's camera cards: a camera's feed sits in the watch's display window, as the kit's
 * readouts do — a `picture-entity` of the camera, and a `picture-glance` over it with a light and
 * the door. The feed is a prefetched frame ([PictureImageStrategy.Inline]), as a widget gets it.
 */
@Composable
private fun RuggedCameras(darkTheme: Boolean) {
  val theme = HaUberTheme.RuggedDigital
  val frame = remember { cameraFrame(640, 360) }
  val strategy = remember(frame) { PictureImageStrategy.Inline { frame } }
  Box(
    Modifier.uiFillMaxSize().background(theme.palette(darkTheme).dashboardBackground).padding(8.dp),
  ) {
    CapturingRemoteContentPreview(profile = androidXExperimental) {
      CompositionLocalProvider(LocalPictureImageStrategy provides strategy) {
        ProvideCardRegistry(defaultRegistry()) {
          ProvideHaUberTheme(theme, darkTheme) {
            RemoteColumn(
              modifier = RemoteModifier.fillMaxWidth(),
              verticalArrangement = RemoteArrangement.spacedBy(HaSpace.M.rdp),
            ) {
              RenderChild(
                card(
                  """{"type":"picture-entity","entity":"camera.driveway","show_name":true,
                      "show_state":true}""",
                ),
                cameraSnapshot,
                RemoteModifier.fillMaxWidth(),
              )
              RenderChild(
                card(
                  """{"type":"picture-glance","title":"Front door",
                      "camera_image":"camera.driveway",
                      "entities":["light.porch","lock.front_door"]}""",
                ),
                cameraSnapshot,
                RemoteModifier.fillMaxWidth(),
              )
            }
          }
        }
      }
    }
  }
}

@Preview(name = "kit: Rugged Digital cameras (dark)", widthDp = 360, heightDp = 560)
@Composable
fun Kit_RuggedDigital_Cameras_Dark() = RuggedCameras(darkTheme = true)

@Preview(name = "kit: Rugged Digital cameras (light)", widthDp = 360, heightDp = 560)
@Composable
fun Kit_RuggedDigital_Cameras_Light() = RuggedCameras(darkTheme = false)

private const val CAMERA_URL = "/api/camera_proxy/camera.driveway?token=preview-token"

private fun entity(id: String, state: String, vararg attributes: Pair<String, String>) =
  id to
    EntityState(
      entityId = id,
      state = state,
      attributes = JsonObject(attributes.associate { (k, v) -> k to JsonPrimitive(v) }),
    )

private val cameraSnapshot =
  HaSnapshot(
    states =
      mapOf(
        entity(
          "camera.driveway",
          "streaming",
          "friendly_name" to "Driveway",
          "entity_picture" to CAMERA_URL,
        ),
        entity("light.porch", "on", "friendly_name" to "Porch"),
        entity("lock.front_door", "locked", "friendly_name" to "Front door"),
      ),
  )

/** A stand-in frame: dusk sky over a lawn, a house with a lit window and a door. */
private fun cameraFrame(width: Int, height: Int): ImageBitmap {
  val bitmap = ImageBitmap(width, height)
  val canvas = Canvas(bitmap)
  val paint = Paint()
  val w = width.toFloat()
  val h = height.toFloat()
  fun rect(color: Color, l: Float, t: Float, r: Float, b: Float) {
    paint.color = color
    canvas.drawRect(Rect(l * w, t * h, r * w, b * h), paint)
  }
  rect(Color(0xFF34506E), 0f, 0f, 1f, 0.62f)
  rect(Color(0xFF4F6E8E), 0f, 0.38f, 1f, 0.62f)
  rect(Color(0xFF3D5A34), 0f, 0.62f, 1f, 1f)
  rect(Color(0xFF8A8F96), 0.42f, 0.62f, 0.58f, 1f)
  rect(Color(0xFFB9AFA0), 0.22f, 0.3f, 0.78f, 0.66f)
  rect(Color(0xFF5B3A2E), 0.45f, 0.42f, 0.55f, 0.66f)
  rect(Color(0xFFFFD27A), 0.28f, 0.38f, 0.38f, 0.5f)
  rect(Color(0xFFFFD27A), 0.62f, 0.38f, 0.72f, 0.5f)
  paint.color = Color(0xFFE9EEF3)
  canvas.drawCircle(Offset(0.86f * w, 0.14f * h), 0.05f * h, paint)
  return bitmap
}
