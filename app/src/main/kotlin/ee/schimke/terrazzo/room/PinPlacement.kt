package ee.schimke.terrazzo.room

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import kotlin.math.abs

/**
 * A wall the headset tracks: its centre (+Y is the wall's normal, pointing into the room, as ARCore
 * for XR reports a plane) and its size along the centre's X and Z axes, in metres.
 */
internal data class WallPlane(val center: Pose, val width: Float, val height: Float)

/**
 * Where a control pinned from [gaze] (the headset's pose: looking along its forward axis) goes on
 * one of [walls]: the point the gaze meets the nearest wall within [reach], set
 * [WALL_OFFSET_METRES] off its surface and facing back into the room, upright. Null when the gaze
 * meets no wall in reach, meets one from behind, or misses its extent; the control is then pinned
 * in the air instead ([airPose]).
 */
internal fun wallPose(
  gaze: Pose,
  walls: List<WallPlane>,
  reach: Float = WALL_REACH_METRES,
): Pose? {
  val origin = gaze.translation
  val direction = gaze.forward
  var best: Pair<Float, Pose>? = null
  for (wall in walls) {
    val normal = wall.center.up
    val facing = direction dot normal
    // Looking at the wall's front: the gaze runs against its normal.
    if (facing > -MIN_FACING) continue
    val distance = ((wall.center.translation - origin) dot normal) / facing
    if (distance <= 0f || distance > reach) continue
    val hit = origin + direction * distance
    val local = hit - wall.center.translation
    if (abs(local dot wall.center.right) > wall.width / 2 + EDGE_TOLERANCE_METRES) continue
    if (abs(local dot wall.center.backward) > wall.height / 2 + EDGE_TOLERANCE_METRES) continue
    if (best == null || distance < best.first) {
      // A panel shows its front along +Z: point that out of the wall, keeping it upright.
      val pose =
        Pose(hit + normal * WALL_OFFSET_METRES, Quaternion.fromLookTowards(normal, Vector3.Up))
      best = distance to pose
    }
  }
  return best?.second
}

/**
 * Where a control pinned from [gaze] goes when no wall is in reach: [distance] ahead of the user,
 * level with their eyes and turned to face them, without the tilt of their head.
 */
internal fun airPose(gaze: Pose, distance: Float = PIN_DISTANCE_METRES): Pose {
  val ahead = gaze.forward.let { Vector3(it.x, 0f, it.z) }
  val flat = if (ahead.length < MIN_FACING) Vector3.Forward else ahead.toNormalized()
  val position = gaze.translation + flat * distance
  // Facing back along the way the user looks.
  return Pose(position, Quaternion.fromLookTowards(-flat, Vector3.Up))
}

/** Far enough to read at a glance, near enough to reach: about arm's length past the hand. */
internal const val PIN_DISTANCE_METRES = 0.8f

/** A wall farther than this is across the room, not the one the user means. */
internal const val WALL_REACH_METRES = 4f

/** Off the wall's surface, so the panel neither flickers into it nor floats away from it. */
internal const val WALL_OFFSET_METRES = 0.02f

/** How far past a tracked wall's edge a gaze still counts: tracked extents run a little short. */
private const val EDGE_TOLERANCE_METRES = 0.3f

/** A gaze closer than this to edge-on (or straight down) doesn't settle on a direction. */
private const val MIN_FACING = 0.15f
