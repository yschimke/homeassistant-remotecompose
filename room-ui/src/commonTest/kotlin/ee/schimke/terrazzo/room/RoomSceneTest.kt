package ee.schimke.terrazzo.room

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomSceneTest {
  private val eye = Vec3(0f, 1.6f, 0f)
  private val ahead = Vec3(0f, 0f, -1f)

  private fun assertNear(expected: Float, actual: Float, tolerance: Float = 0.01f) =
    assertTrue(abs(expected - actual) <= tolerance, "expected $expected, was $actual")

  @Test
  fun aPositiveTurnGoesRight() {
    val turned = rotateY(ahead, 0.5f)
    assertTrue(turned.x > 0f)
    assertTrue(turned.z < 0f)
    assertNear(1f, turned.length)
  }

  @Test
  fun theFirstPanelIsStraightAheadThenRightThenLeft() {
    val row = curvedRow(3, eye, ahead, width = 0.36f)
    assertEquals(3, row.size)
    val (first, firstFacing) = row[0]
    assertNear(0f, first.x)
    assertNear(1.6f, first.y)
    assertNear(-ROW_RADIUS_METRES, first.z)
    assertNear(1f, firstFacing.z)
    assertTrue(row[1].first.x > 0f)
    assertTrue(row[2].first.x < 0f)
    assertNear(row[1].first.x, -row[2].first.x)
  }

  @Test
  fun everyPanelInTheRowFacesTheUser() {
    for ((position, facing) in curvedRow(5, eye, ahead, width = 0.36f)) {
      val toUser = (eye - position).normalized()
      assertNear(1f, toUser dot facing)
      assertNear(ROW_RADIUS_METRES, (position - eye).length)
    }
  }

  @Test
  fun noPanelsNoRow() {
    assertEquals(emptyList(), curvedRow(0, eye, ahead, width = 0.36f))
  }
}
