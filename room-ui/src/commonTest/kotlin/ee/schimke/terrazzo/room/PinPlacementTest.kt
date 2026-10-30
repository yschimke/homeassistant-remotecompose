package ee.schimke.terrazzo.room

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinPlacementTest {

  /** Standing at the origin, eyes 1.6 m up. */
  private val eye = Vec3(0f, 1.6f, 0f)

  /** Looking along -Z, the way both headsets call forward. */
  private val ahead = Vec3(0f, 0f, -1f)

  /** A wall [distance] ahead along -Z, facing back at the user. */
  private fun wallAhead(distance: Float, x: Float = 0f, width: Float = 3f) =
    WallPlane(
      center = Vec3(x, 1.25f, -distance),
      normal = Vec3(0f, 0f, 1f),
      widthAxis = Vec3(1f, 0f, 0f),
      heightAxis = Vec3(0f, 1f, 0f),
      width = width,
      height = 2.5f,
    )

  private fun assertNear(expected: Float, actual: Float, tolerance: Float = 0.01f) =
    assertTrue(abs(expected - actual) <= tolerance, "expected $expected, was $actual")

  @Test
  fun aWallInReachTakesThePinFlushAndFacingTheRoom() {
    val placement = assertNotNull(wallPlacement(eye, ahead, listOf(wallAhead(2f))))
    assertNear(0f, placement.position.x)
    assertNear(1.6f, placement.position.y)
    assertNear(-2f + WALL_OFFSET_METRES, placement.position.z)
    assertEquals(Vec3(0f, 0f, 1f), placement.facing)
    assertTrue(placement.onWall)
  }

  @Test
  fun theNearestWallWins() {
    val placement =
      assertNotNull(wallPlacement(eye, ahead, listOf(wallAhead(3.5f), wallAhead(1.5f))))
    assertNear(-1.5f + WALL_OFFSET_METRES, placement.position.z)
  }

  @Test
  fun theWallHitIsReportedSoAHostCanAnchorToIt() {
    val near = wallAhead(1.5f).copy(id = "near")
    val placement = assertNotNull(wallPlacement(eye, ahead, listOf(wallAhead(3.5f), near)))
    assertEquals(near, placement.wall)
    assertNull(airPlacement(eye, ahead).wall)
  }

  @Test
  fun aWallOutOfReachOrBehindTheUserIsIgnored() {
    assertNull(wallPlacement(eye, ahead, listOf(wallAhead(WALL_REACH_METRES + 1f))))
    assertNull(wallPlacement(eye, ahead, listOf(wallAhead(-2f))))
  }

  @Test
  fun lookingPastAWallsEdgeMissesIt() {
    assertNull(wallPlacement(eye, ahead, listOf(wallAhead(2f, x = 2f, width = 0.4f))))
  }

  @Test
  fun lookingAtAWallFromBehindMissesIt() {
    val away = wallAhead(2f).copy(normal = Vec3(0f, 0f, -1f))
    assertNull(wallPlacement(eye, ahead, listOf(away)))
  }

  @Test
  fun inTheAirThePinIsLevelAheadAndFacesTheUser() {
    // Looking 30° down: the pin still goes level with the eyes, straight ahead.
    val down = Vec3(0f, -sin(Math30), -cos(Math30))
    val placement = airPlacement(eye, down)
    assertNear(1.6f, placement.position.y)
    assertNear(-PIN_DISTANCE_METRES, placement.position.z)
    assertNear(1f, placement.facing.z)
    assertFalse(placement.onWall)
  }

  @Test
  fun pinPlacementFallsBackToTheAir() {
    assertTrue(pinPlacement(eye, ahead, listOf(wallAhead(2f))).onWall)
    assertFalse(pinPlacement(eye, ahead, emptyList()).onWall)
  }

  private companion object {
    const val Math30 = (kotlin.math.PI / 6).toFloat()
  }
}
