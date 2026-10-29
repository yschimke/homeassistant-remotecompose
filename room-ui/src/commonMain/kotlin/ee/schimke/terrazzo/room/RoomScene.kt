package ee.schimke.terrazzo.room

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A panel of the spatial room: where it is, which way its front faces and its size, in metres. Both
 * XR hosts lay their panels out with these ([curvedRow], [pinPlacement]), and [RoomScenePreview]
 * draws them, so a `@Preview` shows the room as the headset would.
 */
data class ScenePanel(
  val id: String,
  val position: Vec3,
  val facing: Vec3,
  val width: Float,
  val height: Float,
)

/**
 * [count] panels [width] metres wide in a row curved around a user at [eye], [radius] ahead of them
 * and [gap] apart, centred on [ahead] (a level direction): the room's controls around the user, the
 * first in the middle of view. Each faces back at the user. Returns each panel's centre and facing.
 */
fun curvedRow(
  count: Int,
  eye: Vec3,
  ahead: Vec3,
  width: Float,
  radius: Float = ROW_RADIUS_METRES,
  gap: Float = ROW_GAP_METRES,
): List<Pair<Vec3, Vec3>> {
  if (count <= 0) return emptyList()
  val forward = Vec3(ahead.x, 0f, ahead.z).normalized()
  val step = (width + gap) / radius
  return List(count) { index ->
    // Centre out: 0, then right and left in turn, so the most important panel is straight ahead.
    val slot = if (index == 0) 0 else if (index % 2 == 1) (index + 1) / 2 else -(index / 2)
    val direction = rotateY(forward, slot * step)
    eye + direction * radius to -direction
  }
}

/** [v] turned [radians] about the vertical axis, to the user's right for a positive angle. */
fun rotateY(v: Vec3, radians: Float): Vec3 {
  // Looking along -Z with +X to the right: a turn right takes -Z towards +X.
  val c = cos(radians)
  val s = sin(radians)
  return Vec3(v.x * c - v.z * s, v.y, v.x * s + v.z * c)
}

/** Degrees in [radians]. */
internal fun degrees(radians: Float): Float = (radians * 180.0 / PI).toFloat()

/** Arm's length past the hand: panels this far away read without leaning in. */
const val ROW_RADIUS_METRES = 1.1f

/** Between neighbouring panels in the row. */
const val ROW_GAP_METRES = 0.05f
