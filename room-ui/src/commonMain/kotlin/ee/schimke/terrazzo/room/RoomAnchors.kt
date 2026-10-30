package ee.schimke.terrazzo.room

/**
 * A headset's persisted anchors, as the room controls use them: the one thing each XR platform
 * implements for pinned controls (Android XR on ARCore for XR in `:app`; a Quest app would on
 * Meta's Spatial SDK). Everything around it — which pins exist, where a new one goes
 * ([pinPlacement]), which room the nearest says the user is in ([nearestPinnedEntity]) — is shared.
 *
 * Anchor ids are the strings stored in [RoomPin.anchorId]: a platform's own anchor UUIDs.
 */
interface RoomAnchors {
  /** The ids of pins whose place the headset found this session. */
  val placed: Set<String>

  /** The ids of pins whose place the headset couldn't load, for the user to remove. */
  val lost: Set<String>

  /** Turns on what pins need (anchor persistence, device and plane tracking); false if refused. */
  fun configure(): Boolean

  /** Loads the anchor of each of [pins] not placed or lost yet. */
  fun load(pins: List<RoomPin>)

  /**
   * A new persisted anchor where the user looks ([pinPlacement] against the walls the headset
   * tracks), or null when the headset can't place or keep one right now.
   */
  suspend fun pinWhereLooking(): PinPlace?

  /** Forgets [anchorId]'s place, on the headset as well. */
  fun unpin(anchorId: String)

  /** Each placed anchor's distance from the user now, in metres. */
  fun distances(): Map<String, Float>
}

/** A new pin's anchor and whether it landed on a wall. */
data class PinPlace(val anchorId: String, val onWall: Boolean)
