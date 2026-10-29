package ee.schimke.terrazzo.quest

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.mruk.MRUKAnchor
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.mruk.MRUKPlane
import com.meta.spatial.mruk.MRUKRoom
import com.meta.spatial.runtime.Scene
import ee.schimke.terrazzo.room.PinPlace
import ee.schimke.terrazzo.room.RoomAnchors
import ee.schimke.terrazzo.room.RoomPin
import ee.schimke.terrazzo.room.Vec3
import ee.schimke.terrazzo.room.WALL_OFFSET_METRES
import ee.schimke.terrazzo.room.WallPlane
import ee.schimke.terrazzo.room.pinPlacement

/**
 * Meta Quest's [RoomAnchors], on MRUK: the walls of the room the user stands in, from Space Setup.
 *
 * A pin is kept relative to a Space Setup anchor ([QuestPinPlace]): the wall it is on, or the room
 * for one in the air. Horizon OS persists those anchors itself, so nothing is saved on the headset
 * for a pin; [load] finds each pin's anchor again and works out its pose in the world, and a pin
 * whose anchor is gone (Space Setup was redone) is [lost].
 */
@Stable
class QuestRoomAnchors(private val scene: Scene, private val mruk: MRUKFeature) : RoomAnchors {
  /** Each placed pin's pose in the world, which the activity puts a panel on. */
  val poses = mutableStateMapOf<String, Pose>()
  private val lostIds = mutableStateListOf<String>()

  override val placed: Set<String>
    get() = poses.keys.toSet()

  override val lost: Set<String>
    get() = lostIds.toSet()

  /** Nothing to turn on: MRUK loads the scene when the activity has the scene permission. */
  override fun configure(): Boolean = mruk.rooms.isNotEmpty()

  override fun load(pins: List<RoomPin>) {
    if (!configure()) return
    for (pin in pins) {
      if (pin.anchorId in poses || pin.anchorId in lostIds) continue
      val pose = QuestPinPlace.decode(pin.anchorId)?.let(::resolve)
      if (pose != null) poses[pin.anchorId] = pose else lostIds += pin.anchorId
    }
  }

  override suspend fun pinWhereLooking(): PinPlace? {
    // Standing outside every room's bounds (a hallway, a doorway): the first room still anchors it.
    val room = mruk.getCurrentRoom() ?: mruk.rooms.firstOrNull() ?: return null
    val head = scene.getViewerPose()
    val walls = walls(room)
    val placement = pinPlacement(head.t.toVec3(), head.forward().toVec3(), walls.map { it.plane })
    val wall = placement.wall?.let { hit -> walls.firstOrNull { it.plane == hit } }
    val place: QuestPinPlace =
      if (wall != null) {
        val offset = placement.position - wall.plane.center
        QuestPinPlace.OnWall(
          wallId = wall.plane.id ?: return null,
          along = offset dot wall.plane.widthAxis,
          up = offset dot wall.plane.heightAxis,
        )
      } else {
        val roomPose = scene.getAnchorPose(room.anchor.handle)
        val inverse = roomPose.inverse()
        val local = inverse.times(placement.position.toVector3())
        val localFacing = inverse.q.times(placement.facing.toVector3())
        QuestPinPlace.InRoom(room.anchor.uuid.toString(), local.toVec3(), localFacing.toVec3())
      }
    val id = place.encode()
    poses[id] = panelPose(placement.position, placement.facing)
    return PinPlace(id, onWall = wall != null)
  }

  override fun unpin(anchorId: String) {
    poses.remove(anchorId)
    lostIds -= anchorId
  }

  override fun distances(): Map<String, Float> {
    val head = scene.getViewerPose().t
    return poses.mapValues { (_, pose) -> pose.t.distanceTo(head) }
  }

  /** The world pose of [place], or null when its Space Setup anchor is gone. */
  private fun resolve(place: QuestPinPlace): Pose? =
    when (place) {
      is QuestPinPlace.OnWall -> {
        val wall =
          mruk.rooms.asSequence().flatMap { walls(it) }.firstOrNull { it.plane.id == place.wallId }
            ?: return null
        val plane = wall.plane
        val position =
          plane.center +
            plane.widthAxis * place.along +
            plane.heightAxis * place.up +
            plane.normal * WALL_OFFSET_METRES
        panelPose(position, plane.normal)
      }
      is QuestPinPlace.InRoom -> {
        val room =
          mruk.rooms.firstOrNull { it.anchor.uuid.toString() == place.roomId } ?: return null
        val roomPose = scene.getAnchorPose(room.anchor.handle)
        val position = roomPose.times(place.position.toVector3()).toVec3()
        val facing = roomPose.q.times(place.facing.toVector3()).toVec3()
        panelPose(position, facing)
      }
    }

  /**
   * [room]'s walls as shared [WallPlane]s, each with its Space Setup UUID, in world space. A wall's
   * normal is turned to point into the room, whichever way its anchor's own axis runs.
   */
  private fun walls(room: MRUKRoom): List<QuestWall> {
    val roomPose = scene.getAnchorPose(room.anchor.handle)
    val roomCentre = roomPose.times(room.roomBounds.center()).toVec3()
    return room.walls.mapNotNull { entity -> wall(entity, roomCentre) }
  }

  private fun wall(entity: Entity, roomCentre: Vec3): QuestWall? {
    val anchor = entity.tryGetComponent<MRUKAnchor>() ?: return null
    val plane = entity.tryGetComponent<MRUKPlane>() ?: return null
    val pose = scene.getAnchorPose(anchor.handle)
    // The plane's extent is in the anchor's own X (across) and Y (up), about its origin.
    val local = Vector3((plane.min.x + plane.max.x) / 2f, (plane.min.y + plane.max.y) / 2f, 0f)
    val centre = pose.times(local).toVec3()
    val axis = pose.forward().toVec3()
    val normal = if ((roomCentre - centre) dot axis >= 0f) axis else -axis
    return QuestWall(
      WallPlane(
        center = centre,
        normal = normal,
        widthAxis = pose.right().toVec3(),
        heightAxis = pose.up().toVec3(),
        width = plane.max.x - plane.min.x,
        height = plane.max.y - plane.min.y,
        id = anchor.uuid.toString(),
      )
    )
  }

  private class QuestWall(val plane: WallPlane)
}

/**
 * A panel's pose at [position] with its front facing [facing], upright. A Spatial SDK panel shows
 * its front against its forward axis (the samples turn a panel to face the user by looking from the
 * user to the panel), so the panel looks along the opposite of [facing].
 */
internal fun panelPose(position: Vec3, facing: Vec3): Pose =
  Pose(position.toVector3(), Quaternion.fromDirection((-facing).toVector3(), Vector3(0f, 1f, 0f)))

internal fun Vector3.toVec3() = Vec3(x, y, z)

internal fun Vec3.toVector3() = Vector3(x, y, z)
