package ee.schimke.terrazzo.room

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinPlacementTest {

  /** Standing at the origin, eyes 1.6 m up, looking along -Z (the headset's forward). */
  private val gaze = Pose(Vector3(0f, 1.6f, 0f), Quaternion.Identity)

  /** A wall [distance] ahead along -Z, its normal (+Y of its pose) pointing back at the user. */
  private fun wallAhead(distance: Float, width: Float = 3f, height: Float = 2.5f) =
    WallPlane(
      center =
        Pose(
          Vector3(0f, 1.25f, -distance),
          // Plane +Y along world +Z (toward the user): its X stays world X, its Z world -Y.
          Quaternion.fromLookTowards(Vector3.Down, Vector3.Backward),
        ),
      width = width,
      height = height,
    )

  private fun assertNear(expected: Float, actual: Float, tolerance: Float = 0.01f) =
    assertTrue(abs(expected - actual) <= tolerance, "expected $expected, was $actual")

  @Test
  fun aWallInReachTakesThePinFlushAndFacingTheRoom() {
    val pose = assertNotNull(wallPose(gaze, listOf(wallAhead(2f))))
    // Where the gaze meets the wall, just off its surface.
    assertNear(0f, pose.translation.x)
    assertNear(1.6f, pose.translation.y)
    assertNear(-2f + WALL_OFFSET_METRES, pose.translation.z)
    // The panel's front (+Z) faces out of the wall, back at the user; it stands upright.
    assertNear(1f, pose.backward.z)
    assertNear(1f, pose.up.y)
  }

  @Test
  fun theNearestWallWins() {
    val pose = assertNotNull(wallPose(gaze, listOf(wallAhead(3.5f), wallAhead(1.5f))))
    assertNear(-1.5f + WALL_OFFSET_METRES, pose.translation.z)
  }

  @Test
  fun aWallOutOfReachOrBehindTheUserIsIgnored() {
    assertNull(wallPose(gaze, listOf(wallAhead(WALL_REACH_METRES + 1f))))
    assertNull(wallPose(gaze, listOf(wallAhead(-2f))))
  }

  @Test
  fun lookingPastAWallsEdgeMissesIt() {
    // A narrow wall off to the side of where the user looks.
    val narrow =
      wallAhead(2f, width = 0.4f).let {
        it.copy(center = Pose(Vector3(2f, 1.25f, -2f), it.center.rotation))
      }
    assertNull(wallPose(gaze, listOf(narrow)))
  }

  @Test
  fun lookingAtAWallFromBehindMissesIt() {
    // The same wall turned to face away from the user.
    val away =
      WallPlane(
        Pose(Vector3(0f, 1.25f, -2f), Quaternion.fromLookTowards(Vector3.Up, Vector3.Forward)),
        3f,
        2.5f,
      )
    assertNull(wallPose(gaze, listOf(away)))
  }

  @Test
  fun inTheAirThePinIsLevelAheadAndFacesTheUser() {
    // Looking down 30°: the pin still goes level with the eyes, straight ahead.
    val lookingDown = Pose(gaze.translation, Quaternion.fromEulerAngles(-30f, 0f, 0f))
    val pose = airPose(lookingDown)
    assertNear(1.6f, pose.translation.y)
    assertNear(-PIN_DISTANCE_METRES, pose.translation.z)
    assertNear(1f, pose.backward.z)
    assertNear(1f, pose.up.y)
  }
}
