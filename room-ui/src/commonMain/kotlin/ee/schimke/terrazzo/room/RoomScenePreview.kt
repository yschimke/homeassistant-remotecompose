package ee.schimke.terrazzo.room

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * The spatial room as a picture, for `@Preview`s: a view from behind and above a user standing at
 * [eye] looking along [ahead], with the floor, the tracked [walls] and each of [panels] drawn in
 * perspective, its real [content] tilted to the way it faces. Not what a headset renders, but the
 * same layout ([curvedRow], [pinPlacement]) from the same numbers, so a change to where panels go
 * shows up in a screenshot test without a device.
 *
 * [dpPerMetre] is the panels' density, as each XR host sets it (`DpPerMeterDisplayOptions` on
 * Quest; Compose for XR's panels are sized in dp at its own density).
 */
@Composable
fun RoomScenePreview(
  eye: Vec3,
  ahead: Vec3,
  walls: List<WallPlane>,
  panels: List<ScenePanel>,
  modifier: Modifier = Modifier,
  dpPerMetre: Float = DEFAULT_DP_PER_METRE,
  colors: RoomSceneColors = RoomSceneColors(),
  content: @Composable (ScenePanel) -> Unit,
) {
  BoxWithConstraints(modifier) {
    val width = constraints.maxWidth.toFloat()
    val height = constraints.maxHeight.toFloat()
    val camera = SceneCamera(eye, ahead, width, height)
    val pxPerDp = LocalDensity.current.density
    Canvas(Modifier.fillMaxSize()) {
      drawRect(colors.background)
      drawFloor(camera, eye, colors)
      // Far walls first, so a nearer one covers it.
      walls.sortedByDescending { camera.depth(it.center) }.forEach { drawWall(camera, it, colors) }
      drawUser(camera, eye, colors)
    }
    // Far panels first, likewise.
    for (panel in panels.sortedByDescending { camera.depth(it.position) }) {
      val centre = camera.project(panel.position) ?: continue
      val panelWidth: Dp = (panel.width * dpPerMetre).dp
      val panelHeight: Dp = (panel.height * dpPerMetre).dp
      val widthPx = panelWidth.value * pxPerDp
      // How big the panel's width is on screen, against the size it is laid out at.
      val scale = camera.scaleAt(panel.position, panel.width) / widthPx
      // Turned by the angle between its facing and the way back to the camera.
      val turn = camera.yawToward(panel)
      key(panel.id) {
        Box(
          Modifier.requiredSize(panelWidth, panelHeight)
            .layout { measurable, constraints ->
              val placeable = measurable.measure(constraints)
              layout(placeable.width, placeable.height) {
                placeable.place(
                  IntOffset(
                    (centre.x - placeable.width / 2f).roundToInt(),
                    (centre.y - placeable.height / 2f).roundToInt(),
                  )
                )
              }
            }
            .graphicsLayer {
              scaleX = scale
              scaleY = scale
              // A positive rotationY swings the layer's right edge away from the viewer; the
              // panel's right edge goes away when the camera is round to its left (a negative
              // turn).
              rotationY = -turn
              cameraDistance = 12f * density
            },
          contentAlignment = Alignment.Center,
        ) {
          content(panel)
        }
      }
    }
  }
}

/** The scene preview's palette: a dim room, so panels stand out as they would in passthrough. */
data class RoomSceneColors(
  val background: Color = Color(0xFF15171C),
  val floor: Color = Color(0xFF2A2E36),
  val wall: Color = Color(0xFF3A3F4A),
  val wallEdge: Color = Color(0xFF59606E),
  val user: Color = Color(0xFF8AB4F8),
)

/** Panels laid out at a phone-like density: 1000 dp a metre, a 0.36 m panel 360 dp wide. */
const val DEFAULT_DP_PER_METRE = 1000f

/**
 * A pinhole camera behind and above the user, looking where they look, a little down: enough to see
 * the floor around them and the panels in front.
 */
private class SceneCamera(eye: Vec3, ahead: Vec3, private val width: Float, height: Float) {
  private val forward =
    Vec3(ahead.x, 0f, ahead.z).normalized().let { Vec3(it.x, -0.25f, it.z).normalized() }
  private val right = Vec3(-forward.z, 0f, forward.x).normalized()
  private val up = cross(right, forward)
  private val position = eye - Vec3(ahead.x, 0f, ahead.z).normalized() * 2.4f + Vec3(0f, 0.7f, 0f)
  // About a 100° field of view across: wide enough for the side walls of a room around the user.
  private val focal = width * 0.42f
  private val centre = Offset(width / 2f, height * 0.42f)

  /** How far ahead of the camera [p] is; nothing at or behind it is drawn. */
  fun depth(p: Vec3): Float = (p - position) dot forward

  fun project(p: Vec3): Offset? {
    val d = p - position
    val z = d dot forward
    if (z < NEAR) return null
    return Offset(centre.x + focal * (d dot right) / z, centre.y - focal * (d dot up) / z)
  }

  /** On-screen pixels a span of [metres] at [p] covers. */
  fun scaleAt(p: Vec3, metres: Float): Float = focal * metres / depth(p).coerceAtLeast(NEAR)

  /** Degrees a panel turns away from facing the camera square on, about the vertical. */
  fun yawToward(panel: ScenePanel): Float {
    val toCamera = position - panel.position
    val facingAngle = atan2(panel.facing.x, panel.facing.z)
    val cameraAngle = atan2(toCamera.x, toCamera.z)
    return degrees(cameraAngle - facingAngle)
  }

  private fun cross(a: Vec3, b: Vec3) =
    Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

  companion object {
    const val NEAR = 0.1f
  }
}

private fun DrawScope.drawFloor(camera: SceneCamera, eye: Vec3, colors: RoomSceneColors) {
  // A grid a metre square around the user, on the floor.
  val range = -4..4
  for (i in range) {
    line(camera, Vec3(eye.x + i, 0f, eye.z - 6f), Vec3(eye.x + i, 0f, eye.z + 2f), colors.floor)
    line(
      camera,
      Vec3(eye.x - 4f, 0f, eye.z + i - 2f),
      Vec3(eye.x + 4f, 0f, eye.z + i - 2f),
      colors.floor,
    )
  }
}

private fun DrawScope.line(camera: SceneCamera, a: Vec3, b: Vec3, color: Color) {
  val pa = camera.project(a) ?: return
  val pb = camera.project(b) ?: return
  drawLine(color, pa, pb, strokeWidth = 2f)
}

private fun DrawScope.drawWall(camera: SceneCamera, wall: WallPlane, colors: RoomSceneColors) {
  val w = wall.widthAxis.normalized() * (wall.width / 2)
  val h = wall.heightAxis.normalized() * (wall.height / 2)
  val corners =
    listOf(wall.center - w - h, wall.center + w - h, wall.center + w + h, wall.center - w + h).map {
      camera.project(it) ?: return
    }
  val path =
    Path().apply {
      moveTo(corners[0].x, corners[0].y)
      corners.drop(1).forEach { lineTo(it.x, it.y) }
      close()
    }
  drawPath(path, colors.wall)
  drawPath(path, colors.wallEdge, style = Stroke(width = 3f))
}

private fun DrawScope.drawUser(camera: SceneCamera, eye: Vec3, colors: RoomSceneColors) {
  val feet = camera.project(Vec3(eye.x, 0f, eye.z)) ?: return
  val head = camera.project(eye) ?: return
  drawLine(colors.user, feet, head, strokeWidth = 4f)
  drawCircle(colors.user, radius = 10f, center = head)
}
