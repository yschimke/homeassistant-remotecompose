package ee.schimke.ha.rc.cards.shutter

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.rc.formatState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * `CoverEntityFeature` bits from Home Assistant (`homeassistant/components/cover/const.py`,
 * mirrored by the frontend's `data/cover.ts`). Only the open / close / stop bits drive the shutter
 * cards' buttons; position and tilt are not offered as controls.
 */
object CoverFeature {
  const val OPEN: Int = 1
  const val CLOSE: Int = 2
  const val SET_POSITION: Int = 4
  const val STOP: Int = 8

  /**
   * What a cover is assumed to support when its state carries no `supported_features`. A real HA
   * cover always reports the attribute; hand-written fixtures and older snapshots often don't, and
   * hiding every button for them would leave a card with no controls at all.
   */
  const val DEFAULT: Int = OPEN or CLOSE or STOP
}

/** How one cover button is drawn. */
enum class CoverButtonState {
  /** Not drawn: the cover doesn't support it, or the card config hides it in this state. */
  Hidden,
  /** Drawn greyed out, with no tap action. */
  Disabled,
  /** Drawn normally; tapping calls the service. */
  Enabled,
}

/** The open (up), stop and close (down) buttons of one cover row. */
data class CoverControls(
  val open: CoverButtonState,
  val stop: CoverButtonState,
  val close: CoverButtonState,
) {
  companion object {
    val AllEnabled: CoverControls =
      CoverControls(CoverButtonState.Enabled, CoverButtonState.Enabled, CoverButtonState.Enabled)
  }
}

/**
 * Per-button state lists from `custom:enhanced-shutter-card`'s `button_up_hide_states`,
 * `button_stop_hide_states` and `button_down_hide_states`. The states are the cover's own plus the
 * card's `partial_open` (an open cover that is neither fully open nor closed).
 */
data class CoverHideStates(
  val open: Set<String> = emptySet(),
  val stop: Set<String> = emptySet(),
  val close: Set<String> = emptySet(),
) {
  companion object {
    val None: CoverHideStates = CoverHideStates()

    fun fromEntry(raw: JsonObject): CoverHideStates =
      CoverHideStates(
        open = raw.stringSet("button_up_hide_states"),
        stop = raw.stringSet("button_stop_hide_states"),
        close = raw.stringSet("button_down_hide_states"),
      )

    private fun JsonObject.stringSet(key: String): Set<String> =
      (this[key] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        ?.toSet()
        .orEmpty()
  }
}

/** The cover's `current_position` (0 = closed, 100 = open), or null when it reports none. */
fun coverPosition(entity: EntityState?): Int? =
  entity
    ?.attributes
    ?.get("current_position")
    ?.let { it as? JsonPrimitive }
    ?.content
    ?.toFloatOrNull()
    ?.toInt()
    ?.coerceIn(0, 100)

private fun supportedFeatures(entity: EntityState): Int =
  (entity.attributes["supported_features"] as? JsonPrimitive)?.content?.toIntOrNull()
    ?: CoverFeature.DEFAULT

private fun isUnavailable(entity: EntityState?): Boolean =
  entity == null || entity.state == "unavailable"

private fun isMoving(entity: EntityState): Boolean =
  entity.state == "opening" || entity.state == "closing"

/** HA frontend `isFullyOpen`: position 100 when the cover reports one, else state `open`. */
private fun isFullyOpen(entity: EntityState): Boolean =
  coverPosition(entity)?.let { it == 100 } ?: (entity.state == "open")

/** HA frontend `isFullyClosed`: position 0 when the cover reports one, else state `closed`. */
private fun isFullyClosed(entity: EntityState): Boolean =
  coverPosition(entity)?.let { it == 0 } ?: (entity.state == "closed")

private fun assumedState(entity: EntityState): Boolean =
  (entity.attributes["assumed_state"] as? JsonPrimitive)?.content == "true"

/**
 * The state name `button_*_hide_states` lists are matched against: the cover's state, except that
 * an `open` cover part-way (0 < position < 100) is `partial_open`, as the upstream card defines it.
 */
fun coverHideState(entity: EntityState?): String? {
  if (entity == null) return null
  val position = coverPosition(entity)
  return if (entity.state == "open" && position != null && position in 1..99) "partial_open"
  else entity.state
}

/**
 * Which of a cover's open / stop / close buttons to show, and which of those can be tapped. Follows
 * Home Assistant's frontend cover controls (`canOpen` / `canClose` in `data/cover.ts`):
 * - a button the cover's `supported_features` lacks is hidden;
 * - open is disabled while the cover is fully open or opening; close while fully closed or closing;
 * - stop is enabled only while the cover is moving (HA's own `canStop` allows it whenever the cover
 *   is available; a stop button on a door at rest does nothing, so here it is greyed);
 * - an `assumed_state` cover (no position feedback) keeps every supported button enabled, as HA
 *   does, since the card can't know where it is;
 * - an unavailable or missing cover disables every button.
 *
 * [hide] then hides buttons the card config asks to hide in the current state.
 */
fun coverControls(
  entity: EntityState?,
  hide: CoverHideStates = CoverHideStates.None,
): CoverControls {
  val features = entity?.let(::supportedFeatures) ?: CoverFeature.DEFAULT
  val unavailable = isUnavailable(entity)
  val assumed = entity != null && assumedState(entity)
  val hideState = coverHideState(entity)

  fun button(feature: Int, hideIn: Set<String>, enabled: (EntityState) -> Boolean) =
    when {
      (features and feature) == 0 -> CoverButtonState.Hidden
      hideState != null && hideState in hideIn -> CoverButtonState.Hidden
      unavailable || entity == null -> CoverButtonState.Disabled
      assumed || enabled(entity) -> CoverButtonState.Enabled
      else -> CoverButtonState.Disabled
    }

  return CoverControls(
    open = button(CoverFeature.OPEN, hide.open) { !isFullyOpen(it) && it.state != "opening" },
    stop = button(CoverFeature.STOP, hide.stop) { isMoving(it) },
    close = button(CoverFeature.CLOSE, hide.close) { !isFullyClosed(it) && it.state != "closing" },
  )
}

/**
 * One state line for a cover row: the formatted state ([formatState], what the hosts push as
 * `<id>.state`), with the position appended only when it adds something — while the cover moves, or
 * when it rests part-way. "Closed" at 0 % and "Open" at 100 % say it once.
 */
fun coverStateLabel(entity: EntityState?): String {
  val base = formatState(entity)
  if (entity == null || entity.state == "unavailable" || entity.state == "unknown") return base
  val position = coverPosition(entity) ?: return base
  val restsAtEnd =
    (entity.state == "closed" && position == 0) || (entity.state == "open" && position == 100)
  return if (restsAtEnd) base else "$base · $position%"
}

/**
 * 0f = fully open, 1f = fully closed. Uses `current_position` when the cover reports one; otherwise
 * a representative value per state, with a moving cover drawn half-way (the motion arrow says which
 * way) and anything else at [unknownFraction].
 */
fun coverClosedFraction(entity: EntityState?, unknownFraction: Float): Float {
  if (entity == null) return unknownFraction
  coverPosition(entity)?.let {
    return 1f - it / 100f
  }
  return when (entity.state) {
    "open" -> 0f
    "closed" -> 1f
    "opening",
    "closing" -> 0.5f
    else -> unknownFraction
  }
}

/** Direction of travel, for the in-drawing motion arrow. */
fun coverMotion(entity: EntityState?): GarageMotion =
  when (entity?.state) {
    "opening" -> GarageMotion.Opening
    "closing" -> GarageMotion.Closing
    else -> GarageMotion.Idle
  }

/** True for covers drawn as a garage door rather than a window shutter. */
fun isGarageLike(entity: EntityState?): Boolean =
  when (entity?.attributes?.get("device_class")?.jsonPrimitive?.content) {
    "garage",
    "gate" -> true
    else -> false
  }
