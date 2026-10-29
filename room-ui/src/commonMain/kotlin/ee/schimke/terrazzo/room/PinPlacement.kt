package ee.schimke.terrazzo.room

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A point or direction in a headset's world space, in metres: +Y up, as both Android XR and Meta's
 * Horizon OS lay out their worlds. Each platform converts its own vector and pose types to and from
 * this, so where a pin goes is worked out once.
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
  operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)

  operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)

  operator fun times(scale: Float): Vec3 = Vec3(x * scale, y * scale, z * scale)

  operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)

  infix fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

  val length: Float
    get() = sqrt(this dot this)

  fun normalized(): Vec3 = length.let { if (it == 0f) this else this * (1f / it) }

  companion object {
    val Up = Vec3(0f, 1f, 0f)
  }
}

/**
 * A wall the headset tracks: its centre, its [normal] (pointing into the room), the two directions
 * along its surface its [width] and [height] run, and those sizes, in metres.
 */
data class WallPlane(
  val center: Vec3,
  val normal: Vec3,
  val widthAxis: Vec3,
  val heightAxis: Vec3,
  val width: Float,
  val height: Float,
)

/**
 * Where a pinned panel goes: its [position], and the direction its front faces ([facing], level or
 * along a wall's normal). Panels stand upright ([Vec3.Up]); the platform turns these into its own
 * pose.
 */
data class PinPlacement(val position: Vec3, val facing: Vec3, val onWall: Boolean)

/**
 * Where a control pinned by a user at [eye] looking along [gaze] goes: on the wall the gaze meets
 * ([wallPlacement]) if one of [walls] is in reach, else in the air ahead ([airPlacement]).
 */
fun pinPlacement(eye: Vec3, gaze: Vec3, walls: List<WallPlane>): PinPlacement =
  wallPlacement(eye, gaze, walls) ?: airPlacement(eye, gaze)

/**
 * The point a gaze from [eye] along [gaze] meets the nearest of [walls] within [reach], set
 * [WALL_OFFSET_METRES] off its surface and facing back into the room. Null when the gaze meets no
 * wall in reach, meets one from behind, or misses its extent.
 */
fun wallPlacement(
  eye: Vec3,
  gaze: Vec3,
  walls: List<WallPlane>,
  reach: Float = WALL_REACH_METRES,
): PinPlacement? {
  val direction = gaze.normalized()
  var best: Pair<Float, PinPlacement>? = null
  for (wall in walls) {
    val normal = wall.normal.normalized()
    val facing = direction dot normal
    // Looking at the wall's front: the gaze runs against its normal.
    if (facing > -MIN_FACING) continue
    val distance = ((wall.center - eye) dot normal) / facing
    if (distance <= 0f || distance > reach) continue
    val hit = eye + direction * distance
    val local = hit - wall.center
    if (abs(local dot wall.widthAxis.normalized()) > wall.width / 2 + EDGE_TOLERANCE_METRES)
      continue
    if (abs(local dot wall.heightAxis.normalized()) > wall.height / 2 + EDGE_TOLERANCE_METRES) {
      continue
    }
    if (best == null || distance < best.first) {
      best = distance to PinPlacement(hit + normal * WALL_OFFSET_METRES, normal, onWall = true)
    }
  }
  return best?.second
}

/**
 * Where a control pinned with no wall in reach goes: [distance] ahead of a user at [eye] looking
 * along [gaze], level with their eyes and turned to face them, without the tilt of their head.
 */
fun airPlacement(eye: Vec3, gaze: Vec3, distance: Float = PIN_DISTANCE_METRES): PinPlacement {
  val level = Vec3(gaze.x, 0f, gaze.z)
  val ahead = if (level.length < MIN_FACING) Vec3(0f, 0f, -1f) else level.normalized()
  return PinPlacement(eye + ahead * distance, facing = -ahead, onWall = false)
}

/** Far enough to read at a glance, near enough to reach: about arm's length past the hand. */
const val PIN_DISTANCE_METRES = 0.8f

/** A wall farther than this is across the room, not the one the user means. */
const val WALL_REACH_METRES = 4f

/** Off the wall's surface, so the panel neither flickers into it nor floats away from it. */
const val WALL_OFFSET_METRES = 0.02f

/** How far past a tracked wall's edge a gaze still counts: tracked extents run a little short. */
private const val EDGE_TOLERANCE_METRES = 0.3f

/** A gaze closer than this to edge-on (or straight down) doesn't settle on a direction. */
private const val MIN_FACING = 0.15f
