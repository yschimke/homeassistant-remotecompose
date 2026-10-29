package ee.schimke.terrazzo.quest

import ee.schimke.terrazzo.room.Vec3

/**
 * Where a pin is on Quest, as stored in `RoomPin.anchorId`.
 *
 * The Spatial SDK has no public API for an app to persist anchors of its own, but Horizon OS keeps
 * the room's Space Setup anchors (each wall, the room itself) with stable UUIDs across sessions. So
 * a pin is kept relative to one of those: on a wall, the [wallId] and the point's offset along the
 * wall's width and height axes from the wall's centre; in the air, the [roomId] and the point and
 * facing in the room anchor's own frame. Loading a pin looks the anchor up again and turns this
 * back into a pose in the world.
 */
sealed interface QuestPinPlace {
  /** On the wall [wallId], [along] metres across it and [up] metres up it from its centre. */
  data class OnWall(val wallId: String, val along: Float, val up: Float) : QuestPinPlace

  /** In the air, at [position] facing [facing], both in the frame of the room [roomId]. */
  data class InRoom(val roomId: String, val position: Vec3, val facing: Vec3) : QuestPinPlace

  /** This place as an anchor id. */
  fun encode(): String =
    when (this) {
      is OnWall -> listOf(WALL, wallId, along, up).joinToString(SEPARATOR)
      is InRoom ->
        listOf(ROOM, roomId, position.x, position.y, position.z, facing.x, facing.y, facing.z)
          .joinToString(SEPARATOR)
    }

  companion object {
    private const val WALL = "wall"
    private const val ROOM = "room"
    private const val SEPARATOR = "|"

    /** The place [anchorId] names, or null for one that isn't a Quest pin (an Android XR UUID). */
    fun decode(anchorId: String): QuestPinPlace? {
      val parts = anchorId.split(SEPARATOR)
      return when (parts.firstOrNull()) {
        WALL -> {
          if (parts.size != 4) return null
          OnWall(
            parts[1],
            parts[2].toFloatOrNull() ?: return null,
            parts[3].toFloatOrNull() ?: return null,
          )
        }
        ROOM -> {
          if (parts.size != 8) return null
          val n = parts.drop(2).map { it.toFloatOrNull() ?: return null }
          InRoom(parts[1], Vec3(n[0], n[1], n[2]), Vec3(n[3], n[4], n[5]))
        }
        else -> null
      }
    }
  }
}
